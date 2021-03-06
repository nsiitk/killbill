/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.jaxrs;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.ws.rs.core.Response.Status;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.billing.jaxrs.json.AccountJson;
import com.ning.billing.jaxrs.json.AuditLogJson;
import com.ning.billing.jaxrs.json.CustomFieldJson;
import com.ning.billing.jaxrs.json.PaymentJson;
import com.ning.billing.jaxrs.json.PaymentMethodJson;
import com.ning.billing.jaxrs.json.RefundJson;
import com.ning.billing.jaxrs.json.TagJson;
import com.ning.billing.jaxrs.resources.JaxrsResource;
import com.ning.billing.util.api.AuditLevel;
import com.ning.http.client.Response;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class TestAccount extends TestJaxrsBase {

    @Test(groups = "slow")
    public void testAccountOk() throws Exception {
        final AccountJson input = createAccount();

        // Retrieves by external key
        final AccountJson retrievedAccount = getAccountByExternalKey(input.getExternalKey());
        Assert.assertTrue(retrievedAccount.equals(input));

        // Try search endpoint
        searchAccount(input, retrievedAccount);

        // Update Account
        final AccountJson newInput = new AccountJson(input.getAccountId(),
                                                     "zozo", 4, input.getExternalKey(), "rr@google.com", 18,
                                                     "USD", null, "UTC", "bl1", "bh2", "", "", "ca", "San Francisco", "usa", "en", "415-255-2991",
                                                     false, false, null, null);
        final AccountJson updatedAccount = updateAccount(input.getAccountId(), newInput);
        Assert.assertTrue(updatedAccount.equals(newInput));

        // Try search endpoint
        searchAccount(input, null);
    }

    @Test(groups = "slow")
    public void testAccountWithBalance() throws Exception {
        final AccountJson accountJson = createAccountNoPMBundleAndSubscriptionAndWaitForFirstInvoice();

        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountJson.getAccountId();
        final Map<String, String> queryParams = ImmutableMap.<String, String>of(JaxrsResource.QUERY_ACCOUNT_WITH_BALANCE, "true");
        final Response response = doGet(uri, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        final String baseJson = response.getResponseBody();
        final AccountJson accountWithBalance = mapper.readValue(baseJson, AccountJson.class);
        final BigDecimal accountBalance = accountWithBalance.getAccountBalance();
        Assert.assertTrue(accountBalance.compareTo(BigDecimal.ZERO) > 0);
    }

    @Test(groups = "slow")
    public void testUpdateNonExistentAccount() throws Exception {
        final AccountJson input = getAccountJson();

        final String baseJson = mapper.writeValueAsString(input);
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + input.getAccountId();
        final Response response = doPut(uri, baseJson, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.NOT_FOUND.getStatusCode());
    }

    @Test(groups = "slow")
    public void testAccountNonExistent() throws Exception {
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/99999999-b103-42f3-8b6e-dd244f1d0747";
        final Response response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.NOT_FOUND.getStatusCode());
    }

    @Test(groups = "slow")
    public void testAccountBadAccountId() throws Exception {
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/yo";
        final Response response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.NOT_FOUND.getStatusCode());
    }

    @Test(groups = "slow")
    public void testAccountPaymentMethods() throws Exception {

        final AccountJson accountJson = createAccount("qwerty", "ytrewq", "qwerty@yahoo.com");
        assertNotNull(accountJson);

        String uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountJson.getAccountId() + "/" + JaxrsResource.PAYMENT_METHODS;
        PaymentMethodJson paymentMethodJson = getPaymentMethodJson(accountJson.getAccountId(), getPaymentMethodCCProperties());
        String baseJson = mapper.writeValueAsString(paymentMethodJson);
        Map<String, String> queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_PAYMENT_METHOD_IS_DEFAULT, "true");

        Response response = doPost(uri, baseJson, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        final String locationCC = response.getHeader("Location");
        Assert.assertNotNull(locationCC);

        // Retrieves by Id based on Location returned
        response = doGetWithUrl(locationCC, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        baseJson = response.getResponseBody();
        final PaymentMethodJson paymentMethodCC = mapper.readValue(baseJson, PaymentMethodJson.class);
        assertTrue(paymentMethodCC.isDefault());
        //
        // Add another payment method
        //
        uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountJson.getAccountId() + "/" + JaxrsResource.PAYMENT_METHODS;
        paymentMethodJson = getPaymentMethodJson(accountJson.getAccountId(), getPaymentMethodPaypalProperties());
        baseJson = mapper.writeValueAsString(paymentMethodJson);

        response = doPost(uri, baseJson, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        final String locationPP = response.getHeader("Location");
        assertNotNull(locationPP);
        response = doGetWithUrl(locationPP, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        baseJson = response.getResponseBody();
        final PaymentMethodJson paymentMethodPP = mapper.readValue(baseJson, PaymentMethodJson.class);
        assertFalse(paymentMethodPP.isDefault());

        //
        // FETCH ALL PAYMENT METHODS
        //
        queryParams = new HashMap<String, String>();
        response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        baseJson = response.getResponseBody();
        List<PaymentMethodJson> paymentMethods = mapper.readValue(baseJson, new TypeReference<List<PaymentMethodJson>>() {});
        assertEquals(paymentMethods.size(), 2);

        //
        // CHANGE DEFAULT
        //
        uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountJson.getAccountId() + "/" + JaxrsResource.PAYMENT_METHODS + "/" + paymentMethodPP.getPaymentMethodId() + "/" + JaxrsResource.PAYMENT_METHODS_DEFAULT_PATH_POSTFIX;
        response = doPut(uri, "{}", DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        response = doGetWithUrl(locationPP, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        baseJson = response.getResponseBody();
        final PaymentMethodJson paymentMethodPPDefault = mapper.readValue(baseJson, PaymentMethodJson.class);
        assertTrue(paymentMethodPPDefault.isDefault());

        //
        // DELETE NON DEFAULT PM
        //
        uri = JaxrsResource.PAYMENT_METHODS_PATH + "/" + paymentMethodCC.getPaymentMethodId();
        response = doDelete(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        //
        // FETCH ALL PAYMENT METHODS
        //
        uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountJson.getAccountId() + "/" + JaxrsResource.PAYMENT_METHODS;
        queryParams = new HashMap<String, String>();
        response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        baseJson = response.getResponseBody();
        paymentMethods = mapper.readValue(baseJson, new TypeReference<List<PaymentMethodJson>>() {});
        assertEquals(paymentMethods.size(), 1);

        //
        // DELETE DEFAULT PAYMENT METHOD (without special flag first)
        //
        uri = JaxrsResource.PAYMENT_METHODS_PATH + "/" + paymentMethodPP.getPaymentMethodId();
        response = doDelete(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.BAD_REQUEST.getStatusCode());

        //
        // RETRY TO DELETE DEFAULT PAYMENT METHOD (with special flag this time)
        //
        uri = JaxrsResource.PAYMENT_METHODS_PATH + "/" + paymentMethodPP.getPaymentMethodId();
        queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_DELETE_DEFAULT_PM_WITH_AUTO_PAY_OFF, "true");

        response = doDelete(uri, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        // CHECK ACCOUNT IS NOW AUTO_PAY_OFF

        uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountJson.getAccountId() + "/" + JaxrsResource.TAGS;
        response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        baseJson = response.getResponseBody();
        List<TagJson> tagsJson = mapper.readValue(baseJson, new TypeReference<List<TagJson>>() {});
        Assert.assertEquals(tagsJson.size(), 1);
        TagJson tagJson = tagsJson.get(0);
        Assert.assertEquals(tagJson.getTagDefinitionName(), "AUTO_PAY_OFF");
        Assert.assertEquals(tagJson.getTagDefinitionId(), new UUID(0, 1).toString());

        // FETCH ACCOUNT AGAIN AND CHECK THERE IS NO DEFAULT PAYMENT METHOD SET
        uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountJson.getAccountId();
        response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);

        AccountJson updatedAccountJson = mapper.readValue(response.getResponseBody(), AccountJson.class);
        Assert.assertEquals(updatedAccountJson.getAccountId(), accountJson.getAccountId());
        Assert.assertNull(updatedAccountJson.getPaymentMethodId());

        //
        // FINALLY TRY TO REMOVE AUTO_PAY_OFF WITH NO DEFAULT PAYMENT METHOD ON ACCOUNT
        //
        uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountJson.getAccountId() + "/" + JaxrsResource.TAGS;
        queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_TAGS, new UUID(0, 1).toString());
        response = doDelete(uri, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.BAD_REQUEST.getStatusCode());

    }

    @Test(groups = "slow")
    public void testAccountPaymentsWithRefund() throws Exception {
        final AccountJson accountJson = createAccountWithPMBundleAndSubscriptionAndWaitForFirstInvoice();

        // Verify payments
        final List<PaymentJson> objFromJson = getPaymentsForAccount(accountJson.getAccountId());
        Assert.assertEquals(objFromJson.size(), 1);

        // Verify refunds
        final List<RefundJson> objRefundFromJson = getRefundsForAccount(accountJson.getAccountId());
        Assert.assertEquals(objRefundFromJson.size(), 0);
    }

    @Test(groups = "slow")
    public void testTags() throws Exception {
        final AccountJson input = createAccount();
        final String accountId = input.getAccountId();
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountId + "/" + JaxrsResource.TAGS;
        final String accountTagsUrl = getUrlFromUri(uri);
        // Use tag definition for AUTO_PAY_OFF
        final String autoPayOffId = new UUID(0, 1).toString();

        // Add a tag
        Response response = doPost(uri, null, ImmutableMap.<String, String>of(JaxrsResource.QUERY_TAGS, autoPayOffId), DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        // Retrieves all tags
        response = doGetWithUrl(accountTagsUrl, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        final List<TagJson> tags1 = mapper.readValue(response.getResponseBody(), new TypeReference<List<TagJson>>() {});
        Assert.assertEquals(tags1.size(), 1);
        Assert.assertEquals(tags1.get(0).getTagDefinitionId(), autoPayOffId);

        // Verify adding the same tag a second time doesn't do anything
        response = doPost(uri, null, ImmutableMap.<String, String>of(JaxrsResource.QUERY_TAGS, autoPayOffId), DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        // Retrieves all tags again
        response = doGetWithUrl(accountTagsUrl, ImmutableMap.<String, String>of(JaxrsResource.QUERY_AUDIT, AuditLevel.FULL.toString()), DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        final List<TagJson> tags2 = mapper.readValue(response.getResponseBody(), new TypeReference<List<TagJson>>() {});
        Assert.assertEquals(tags2, tags1);

        // Verify audit logs
        Assert.assertEquals(tags2.get(0).getAuditLogs().size(), 1);
        final AuditLogJson auditLogJson = tags2.get(0).getAuditLogs().get(0);
        Assert.assertEquals(auditLogJson.getChangeType(), "INSERT");
        Assert.assertEquals(auditLogJson.getChangedBy(), createdBy);
        Assert.assertEquals(auditLogJson.getReasonCode(), reason);
        Assert.assertEquals(auditLogJson.getComments(), comment);
        Assert.assertNotNull(auditLogJson.getChangeDate());
        Assert.assertNotNull(auditLogJson.getUserToken());
    }

    @Test(groups = "slow")
    public void testCustomFields() throws Exception {
        final AccountJson accountJson = createAccount("yoyoq", "gfgrqe", "yoyoq@yahoo.com");
        assertNotNull(accountJson);

        final List<CustomFieldJson> customFields = new LinkedList<CustomFieldJson>();
        customFields.add(new CustomFieldJson("1", "value1", null));
        customFields.add(new CustomFieldJson("2", "value2", null));
        customFields.add(new CustomFieldJson("3", "value3", null));
        final String baseJson = mapper.writeValueAsString(customFields);

        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountJson.getAccountId() + "/" + JaxrsResource.CUSTOM_FIELDS;
        Response response = doPost(uri, baseJson, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        // Retrieves by Id based on Location returned
        final String url = getUrlFromUri(uri);
        response = doGetWithUrl(url, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
    }

    private void searchAccount(final AccountJson input, @Nullable final AccountJson output) throws Exception {
        // Search by id
        if (output != null) {
            doSearchAccount(input.getAccountId(), output);
        }

        // Search by name
        doSearchAccount(input.getName(), output);

        // Search by email
        doSearchAccount(input.getEmail(), output);

        // Search by company name
        doSearchAccount(input.getCompany(), output);

        // Search by external key.
        // Note: we will always find a match since we don't update it
        final List<AccountJson> accountsByExternalKey = searchAccountsByKey(input.getExternalKey());
        Assert.assertEquals(accountsByExternalKey.size(), 1);
        Assert.assertEquals(accountsByExternalKey.get(0).getAccountId(), input.getAccountId());
        Assert.assertEquals(accountsByExternalKey.get(0).getExternalKey(), input.getExternalKey());
    }

    private void doSearchAccount(final String key, @Nullable final AccountJson output) throws Exception {
        final List<AccountJson> accountsByKey = searchAccountsByKey(key);
        if (output == null) {
            Assert.assertEquals(accountsByKey.size(), 0);
        } else {
            Assert.assertEquals(accountsByKey.size(), 1);
            Assert.assertEquals(accountsByKey.get(0), output);
        }
    }
}
