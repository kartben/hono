/**
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */

package org.eclipse.hono.service.registration;

import static org.junit.Assert.*;

import org.eclipse.hono.config.SignatureSupportingConfigProperties;
import org.junit.Test;

import io.vertx.core.Vertx;


/**
 * Tests verifying behavior of {@link RegistrationAssertionHelperImpl}.
 *
 */
public class RegistrationAssertionHelperImplTest {

    private final Vertx vertx = Vertx.vertx();

    /**
     * Verifies that the helper asserts a minimum length of 32 bytes for shared secrets.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testForSigningRejectsShortSecret() {

        final String shortSecret = "01234567890123456"; // not 32 bytes long
        RegistrationAssertionHelperImpl.forSharedSecret(shortSecret, 10);
    }

    /**
     * Verifies that signatures created using an RSA private key can be validated using the corresponding public key.
     */
    @Test
    public void testForSigningWorksWithRsaSignatures() {

        final SignatureSupportingConfigProperties props = new SignatureSupportingConfigProperties();
        props.setKeyPath("target/certs/hono-messaging-key.pem");
        props.setCertPath("target/certs/hono-messaging-cert.pem");

        final RegistrationAssertionHelper factory = RegistrationAssertionHelperImpl.forSigning(vertx, props);
        final String assertion = factory.getAssertion("tenant", "device");
        assertNotNull(assertion);
        final RegistrationAssertionHelper validator = RegistrationAssertionHelperImpl.forValidating(vertx, props);
        assertTrue(validator.isValid(assertion, "tenant", "device"));

    }

}
