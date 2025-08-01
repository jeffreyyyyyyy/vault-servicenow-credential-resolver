/*
 * Copyright (c) HashiCorp, Inc.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.snc.discovery;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Function;

public class CredentialResolver {
    private static final CloseableHttpClient defaultHTTPClient = HttpClients.createDefault();
    private static final Gson gson = new Gson();
    private final Function<String, String> getProperty;

    public CredentialResolver(Function<String, String> getProperty) {
        this.getProperty = getProperty;
    }

    // Populated keys on resolve's input `Map args`
    public static final String ARG_ID = "id"; // the string identifier as configured on the ServiceNow instance
    public static final String ARG_IP = "ip"; // a dotted-form string IPv4 address (like "10.22.231.12") of the target system
    public static final String ARG_TYPE = "type"; // the string type (ssh, snmp, etc.) of credential
    public static final String ARG_MID = "mid"; // the MID server making the request

    // Keys that may optionally be populated on resolve's output Map
    public static final String VAL_USER = "user"; // the string username for the credential
    public static final String VAL_PSWD = "pswd"; // the string password for the credential
    public static final String VAL_PASSPHRASE = "passphrase"; // the string pass phrase for the credential
    public static final String VAL_PKEY = "pkey"; // the string private key for the credential
    public static final String VAL_AUTHPROTO = "authprotocol"; // the string authentication protocol for the credential
    public static final String VAL_AUTHKEY = "authkey"; // the string authentication key for the credential
    public static final String VAL_PRIVPROTO = "privprotocol"; // the string privacy protocol for the credential
    public static final String VAL_PRIVKEY = "privkey"; // the string privacy key for the credential
    public static final String VAL_BEARER = "bearer_token"; // the string brearer token for the credential

    public static final String PROP_ADDRESS = "mid.external_credentials.vault.address"; // The address of Vault Agent, as resolvable from the MID server
    public static final String PROP_CA = "mid.external_credentials.vault.ca"; // The custom CA to trust in PEM format
    public static final String PROP_TLS_SKIP_VERIFY = "mid.external_credentials.vault.tls_skip_verify"; // Whether to skip TLS verification

    /**
     * Resolve a credential.
     */
    public Map resolve(Map args) throws IOException {
        String vaultAddress = getProperty.apply(PROP_ADDRESS);
        String vaultCA = getProperty.apply(PROP_CA);
        String tlsSkipVerifyRaw = getProperty.apply(PROP_TLS_SKIP_VERIFY);

        if (vaultAddress == null || vaultAddress.equals("")) {
            throw new RuntimeException(String.format("MID server property %s is empty but required", PROP_ADDRESS));
        }

        boolean tlsSkipVerify = false;
        if (tlsSkipVerifyRaw != null && !tlsSkipVerifyRaw.equals("")) {
            tlsSkipVerify = Boolean.parseBoolean(tlsSkipVerifyRaw);
        }

        String id = (String) args.get(ARG_ID);

        String body = send(new HttpGet(vaultAddress + "/v1/" + id), vaultCA, tlsSkipVerify);
        System.err.println("Successfully queried Vault for credential id: "+id);

        Map<String, String> result = extractKeys(body);
        CredentialType type = lookupByName((String) args.get(ARG_TYPE));
        validateResult(result, type);
        return result;
    }

    /**
     * Return the ServiceNow API version supported by this class.
     */
    public String getVersion() {
        return "1.0";
    }

    public static String send(HttpUriRequest req, String vaultCA, boolean tlsSkipVerify) throws IOException {
        SSLContext sslContext;
        try {
            TLSConfig tlsConfig = new TLSConfig().verify(!tlsSkipVerify);
            if (vaultCA != null && !vaultCA.equals("")) {
                tlsConfig = tlsConfig.pemUTF8(vaultCA);
            }
            sslContext = tlsConfig.build().getSslContext();
        } catch (TLSConfig.TLSException e) {
            throw new RuntimeException("Failed to configure SSL context: " + e);
        }

        CloseableHttpClient httpClient;
        if (sslContext != null) {
            SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
                sslContext,
                null,
                null,
                SSLConnectionSocketFactory.getDefaultHostnameVerifier());
            httpClient = HttpClients.custom()
                .setSSLSocketFactory(sslsf)
                .build();
        } else {
            httpClient = defaultHTTPClient;
        }

        String body = null;
        req.setHeader("accept", "application/json");
        req.setHeader("X-Vault-Request", "true");
        try (CloseableHttpResponse response = httpClient.execute(req)) {
            if (response.getEntity() != null) {
                Scanner s = new Scanner(response.getEntity().getContent()).useDelimiter("\\A");
                body = s.hasNext() ? s.next() : "";
            }

            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                String message = String.format("Failed to query Vault URL: %s.", req.getURI());
                // Try to parse the error as a Vault error and extract relevant fields.
                try {
                    VaultError json = gson.fromJson(body, VaultError.class);
                    if (json != null) {
                        final String[] errors = json.getErrors();
                        if (errors != null && errors.length > 0) {
                            message += String.format(" Errors: %s.", Arrays.toString(errors));
                        }
                        final String[] warnings = json.getWarnings();
                        if (warnings != null && warnings.length > 0) {
                            message += String.format(" Warnings: %s.", Arrays.toString(warnings));
                        }
                    }
                } catch (Exception e) {
                    // Failed to parse the body as a Vault error, just include the body.
                    message += "\n\n" + body;
                }

                throw new HttpResponseException(status, message);
            }
        }

        return body;
    }

    private Map<String, String> extractKeys(String vaultResponse) {
        Gson gson = new Gson();
        VaultSecret secret = gson.fromJson(vaultResponse, VaultSecret.class);
        JsonObject data = secret.getData();

        if (data == null) {
            throw new RuntimeException("No data found in Vault secret");
        }

        // Check for embedded "data" object to handle kv-v2.
        if (data.has("data")) {
            try {
                data = data.get("data").getAsJsonObject();
            } catch (IllegalStateException e) {
                // If it's not a JsonObject, then it's not kv-v2 and we use the top-level "Data" field.
            }
        }

        // access_key for AWS secret engine
        ValueAndSource username = valueAndSourceFromData(data, "access_key", "username");
        // secret_key for AWS secret engine, current_password for AD secret engine
        ValueAndSource password = valueAndSourceFromData(data, "secret_key", "current_password", "password");
        ValueAndSource privateKey = valueAndSourceFromData(data, "private_key");
        ValueAndSource passphrase = valueAndSourceFromData(data, "passphrase");

        ValueAndSource authprotocol = valueAndSourceFromData(data, "authprotocol");
        ValueAndSource authkey = valueAndSourceFromData(data, "authkey");
        ValueAndSource privprotocol = valueAndSourceFromData(data, "privprotocol");
        ValueAndSource privkey = valueAndSourceFromData(data, "privkey");
        ValueAndSource bearer = valueAndSourceFromData(data, "bearer_token");

        System.err.printf("Setting values from fields %s=%s, %s=%s, %s=%s, %s=%s, %s=%s, %s=%s, %s=%s, %s=%s%n",
                VAL_USER, username.source,
                VAL_PSWD, password.source,
                VAL_PKEY, privateKey.source,
                VAL_PASSPHRASE, passphrase.source,
                VAL_AUTHPROTO, authprotocol.source,
                VAL_AUTHKEY, authkey.source,
                VAL_PRIVPROTO, privprotocol.source,
                VAL_PRIVKEY, privkey.source,
                VAL_BEARER, bearer.source);
               
        HashMap<String, String> result = new HashMap<>();
        if (username.value != null) {
            result.put(VAL_USER, username.value);
        }
        if (password.value != null) {
            result.put(VAL_PSWD, password.value);
        }
        if (privateKey.value != null) {
            result.put(VAL_PKEY, privateKey.value);
        }
        if (passphrase.value != null) {
            result.put(VAL_PASSPHRASE, passphrase.value);
        }
         if (authprotocol.value != null) {
            result.put(VAL_AUTHPROTO, authprotocol.value);
        }
        if (authkey.value != null) {
            result.put(VAL_AUTHKEY, authkey.value);
        }
        if (privprotocol.value != null) {
            result.put(VAL_PRIVPROTO, privprotocol.value);
        }
        if (privkey.value != null) {
            result.put(VAL_PRIVKEY, privkey.value);
        }
        if (bearer.value != null) {
            result.put(VAL_BEARER, bearer.value);
        }

        return result;
    }

    public void validateResult(Map<String, String> result, CredentialType type) {
        if (result.size() == 0) {
            throw new RuntimeException("No fields to extract from Vault secret");
        }

        if (type == null) {
            return;
        }

        for (String expected : type.expectedFields()) {
            if (!result.containsKey(expected)) {
                throw new RuntimeException(String.format("Expected '%s' field for credential type %s", expected, type.name()));
            }
        }
    }

    private static final Map<String, CredentialType> nameIndex = new HashMap<>(CredentialType.values().length);
    static {
        for (CredentialType type : CredentialType.values()) {
            nameIndex.put(type.name(), type);
        }
    }
    private static CredentialType lookupByName(String name) {
        return nameIndex.get(name);
    }

    enum CredentialType {
        basic                               (new String[]{VAL_USER, VAL_PSWD}),
        windows                             (new String[]{VAL_USER, VAL_PSWD}),
        ssh_password                        (new String[]{VAL_USER, VAL_PSWD}),
        vmware                              (new String[]{VAL_USER, VAL_PSWD}),
        jdbc                                (new String[]{VAL_USER, VAL_PSWD}),
        jms                                 (new String[]{VAL_USER, VAL_PSWD}),
        aws                                 (new String[]{VAL_USER, VAL_PSWD}),
        ssh_private_key                     (new String[]{VAL_USER, VAL_PKEY}),
        sn_cfg_ansible                      (new String[]{VAL_USER, VAL_PKEY}),
        sn_disco_certmgmt_certificate_ca    (new String[]{VAL_USER, VAL_PKEY}),
        cfg_chef_credentials                (new String[]{VAL_USER, VAL_PKEY}),
        infoblox                            (new String[]{VAL_USER, VAL_PKEY}),
        api_key                             (new String[]{VAL_USER, VAL_PKEY}),
        snmpv3                              (new String[]{VAL_USER, VAL_AUTHPROTO, VAL_AUTHKEY, VAL_PRIVPROTO, VAL_PRIVKEY}),
        bearer                              (new String[]{VAL_BEARER});
        private final String[] expectedFields;

        CredentialType(String[] expectedFields) {
            this.expectedFields = expectedFields;
        }

        public String[] expectedFields() {
            return expectedFields;
        }
    }

    // Metadata class to help report which fields keys were extracted from.
    private static class ValueAndSource {
        private final String value;
        private final String source;

        ValueAndSource(String value, String source) {
            this.value = value;
            this.source = source;
        }
    }

    // The first key that exists in data will be extracted and returned.
    private ValueAndSource valueAndSourceFromData(JsonObject data, String ...keys) {
        for (String key : keys) {
            if (data.has(key)) {
                return new ValueAndSource(data.get(key).getAsString(), key);
            }
        }

        return new ValueAndSource(null, null);
    }
}
