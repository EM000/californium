/*******************************************************************************
 * Copyright (c) 2019 Bosch Software Innovations GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Achim Kraus (Bosch Software Innovations GmbH) - initial implementation.
 ******************************************************************************/
package org.eclipse.californium.scandium.dtls.cipher;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;

/**
 * Thread local cipher.
 * 
 * Uses {@link ThreadLocal} to cache calls to
 * {@link Cipher#getInstance(String)}.
 */
public class ThreadLocalCipher extends ThreadLocalCrypto<Cipher> {

	/**
	 * {@inheritDoc} Create thread local cipher.
	 * 
	 * Try to instance the cipher for the provided transformation.
	 * 
	 * @param transformation transformation. Passed to
	 *            {@link Cipher#getInstance(String)}.
	 */
	public ThreadLocalCipher(final String transformation) {
		super(new Factory<Cipher>() {

			@Override
			public Cipher getInstance() throws GeneralSecurityException {
				return Cipher.getInstance(transformation);
			}

		});
	}

}
