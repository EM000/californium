/*******************************************************************************
 * Copyright (c) 2015, 2016 Bosch Software Innovations GmbH and others.
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
 *    Kai Hudalla (Bosch Software Innovations GmbH) - initial creation
 *    Kai Hudalla (Bosch Software Innovations GmbH) - add method for retrieving
 *                                                    trust anchor
 *    Kai Hudalla (Bosch Software Innovations GmbH) - explicitly support retrieving client & server keys
 *                                                    and certificate chains 
 ******************************************************************************/
package org.eclipse.californium.scandium.dtls;

import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.californium.elements.util.ClockUtil;
import org.eclipse.californium.elements.util.DatagramWriter;
import org.eclipse.californium.elements.util.SslContextUtil;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.util.ServerName;

public final class DtlsTestTools {

	public static final char[] TRUST_STORE_PASSWORD = "rootPass".toCharArray();
	public static final char[] KEY_STORE_PASSWORD = "endPass".toCharArray();
	public static final String KEY_STORE_LOCATION = "certs/keyStore.jks";
	public static final String TRUST_STORE_LOCATION = "certs/trustStore.jks";
	public static final String SERVER_NAME = "server";
	public static final String CLIENT_NAME = "client";
	public static final String ROOT_CA_ALIAS = "root";
	public static final String NO_SIGNING_ALIAS = "nosigning";
	public static final long MAX_SEQUENCE_NO = 281474976710655L; // 2^48 - 1
	private static SslContextUtil.Credentials clientCredentials;
	private static SslContextUtil.Credentials serverCredentials;
	private static X509Certificate[] trustedCertificates;
	private static X509Certificate rootCaCertificate;
	private static X509Certificate nosigningCertificate; // a certificate without digitalSignature value in keyusage

	static {
		try {
			// load key stores once only
			clientCredentials = SslContextUtil.loadCredentials(
					SslContextUtil.CLASSPATH_SCHEME + KEY_STORE_LOCATION, "client", KEY_STORE_PASSWORD,
					KEY_STORE_PASSWORD);
			serverCredentials = SslContextUtil.loadCredentials(
					SslContextUtil.CLASSPATH_SCHEME + KEY_STORE_LOCATION, "server", KEY_STORE_PASSWORD,
					KEY_STORE_PASSWORD);
			Certificate[] certificates = SslContextUtil.loadTrustedCertificates(
					SslContextUtil.CLASSPATH_SCHEME + TRUST_STORE_LOCATION, null, TRUST_STORE_PASSWORD);

			trustedCertificates = SslContextUtil.asX509Certificates(certificates);
			certificates = SslContextUtil.loadTrustedCertificates(
					SslContextUtil.CLASSPATH_SCHEME + TRUST_STORE_LOCATION, ROOT_CA_ALIAS, TRUST_STORE_PASSWORD);
			rootCaCertificate = (X509Certificate) certificates[0];
			X509Certificate[] chain = SslContextUtil.loadCertificateChain(
					SslContextUtil.CLASSPATH_SCHEME + KEY_STORE_LOCATION, NO_SIGNING_ALIAS, KEY_STORE_PASSWORD);
			nosigningCertificate = chain[0];
		} catch (IOException | GeneralSecurityException e) {
			// nothing we can do
		}
	}

	private DtlsTestTools() {
	}

	public static Record getRecordForMessage(int epoch, int seqNo, DTLSMessage msg, InetSocketAddress peer) {
		byte[] dtlsRecord = newDTLSRecord(msg.getContentType().getCode(), epoch,
				seqNo, msg.toByteArray());
		List<Record> list = Record.fromByteArray(dtlsRecord, peer, null, ClockUtil.nanoRealtime());
		assertFalse("Should be able to deserialize DTLS Record from byte array", list.isEmpty());
		return list.get(0);
	}

	public static final byte[] newDTLSRecord(int typeCode, int epoch, long sequenceNo, byte[] fragment) {

		ProtocolVersion protocolVer = new ProtocolVersion();
		// the record header contains a type code, version, epoch, sequenceNo, length
		DatagramWriter writer = new DatagramWriter();
		writer.write(typeCode, 8);
		writer.write(protocolVer.getMajor(), 8);
		writer.write(protocolVer.getMinor(), 8);
		writer.write(epoch, 16);
		writer.writeLong(sequenceNo, 48);
		writer.write(fragment.length, 16);
		writer.writeBytes(fragment);
		return writer.toByteArray();
	}

	public static final byte[] generateCookie(InetSocketAddress endpointAddress, ClientHello clientHello)
			throws GeneralSecurityException {
		
		// Cookie = HMAC(Secret, Client-IP, Client-Parameters)
		Mac hmac = Mac.getInstance("HmacSHA256");
		hmac.init(new SecretKeySpec("generate cookie".getBytes(), "Mac"));
		// Client-IP
		hmac.update(endpointAddress.toString().getBytes());

		// Client-Parameters
		hmac.update((byte) clientHello.getClientVersion().getMajor());
		hmac.update((byte) clientHello.getClientVersion().getMinor());
		hmac.update(clientHello.getRandom().getBytes());
		hmac.update(clientHello.getSessionId().getBytes());
		hmac.update(CipherSuite.listToByteArray(clientHello.getCipherSuites()));
		hmac.update(CompressionMethod.listToByteArray(clientHello.getCompressionMethods()));
		return hmac.doFinal();
	}

	public static byte[] newClientCertificateTypesExtension(int... types) {
		DatagramWriter writer = new DatagramWriter();
		writer.write(types.length, 8);
		for (int type : types) {
			writer.write(type, 8);
		}
		return newHelloExtension(19, writer.toByteArray());
	}

	public static byte[] newServerCertificateTypesExtension(int... types) {
		DatagramWriter writer = new DatagramWriter();
		writer.write(types.length, 8);
		for (int type : types) {
			writer.write(type, 8);
		}
		return newHelloExtension(20, writer.toByteArray());
	}

	public static byte[] newSupportedEllipticCurvesExtension(int... curveIds) {
		DatagramWriter writer = new DatagramWriter();
		writer.write(curveIds.length * 2, 16);
		for (int type : curveIds) {
			writer.write(type, 16);
		}
		return newHelloExtension(10, writer.toByteArray());
	}

	public static byte[] newMaxFragmentLengthExtension(int lengthCode) {
		return newHelloExtension(1, new byte[]{(byte) lengthCode});
	}

	public static byte[] newServerNameExtension(final String hostName) {

		byte[] name = hostName.getBytes(ServerName.CHARSET);
		DatagramWriter writer = new DatagramWriter();
		writer.write(name.length + 3, 16); //server_name_list_length
		writer.writeByte((byte) 0x00);
		writer.write(name.length, 16);
		writer.writeBytes(name);
		return newHelloExtension(0, writer.toByteArray());
	}

	public static byte[] newHelloExtension(int typeCode, byte[] extensionBytes) {
		DatagramWriter writer = new DatagramWriter();
		writer.write(typeCode, 16);
		writer.write(extensionBytes.length, 16);
		writer.writeBytes(extensionBytes);
		return writer.toByteArray();
	}

	public static X509Certificate[] getServerCertificateChain()	throws IOException, GeneralSecurityException {
		X509Certificate[] certificateChain = serverCredentials.getCertificateChain();
		return Arrays.copyOf(certificateChain, certificateChain.length);
	}

	public static X509Certificate[] getClientCertificateChain()	throws IOException, GeneralSecurityException {
		X509Certificate[] certificateChain = clientCredentials.getCertificateChain();
		return Arrays.copyOf(certificateChain, certificateChain.length);
	}

	/**
	 * Gets the server's private key from the example key store.
	 * 
	 * @return the key
	 * @throws IOException if the key store cannot be read
	 * @throws GeneralSecurityException if the key cannot be found
	 */
	public static PrivateKey getPrivateKey() throws IOException, GeneralSecurityException {
		return serverCredentials.getPrivateKey();
	}

	/**
	 * Gets the client's private key from the example key store.
	 * 
	 * @return the key
	 * @throws IOException if the key store cannot be read
	 * @throws GeneralSecurityException if the key cannot be found
	 */
	public static PrivateKey getClientPrivateKey() throws IOException, GeneralSecurityException {
		return clientCredentials.getPrivateKey();
	}

	/**
	 * Gets the server's public key from the example key store.
	 * 
	 * @return The key.
	 * @throws IOException if the key store cannot be read
	 * @throws GeneralSecurityException if the key cannot be found
	 * @throws IllegalStateException if the key store does not contain a server certificate chain.
	 */
	public static PublicKey getPublicKey() throws IOException, GeneralSecurityException {
		return serverCredentials.getCertificateChain()[0].getPublicKey();
	}

	/**
	 * Gets the client's public key from the example key store.
	 * 
	 * @return The key.
	 * @throws IOException if the key store cannot be read
	 * @throws GeneralSecurityException if the key cannot be found
	 * @throws IllegalStateException if the key store does not contain a client certificate chain.
	 */
	public static PublicKey getClientPublicKey() throws IOException, GeneralSecurityException {
		return clientCredentials.getCertificateChain()[0].getPublicKey();
	}

	/**
	 * Gets the trusted anchor certificates from the example trust store.
	 * 
	 * @return The trusted certificates.
	 */
	public static X509Certificate[] getTrustedCertificates() {
		return trustedCertificates;
	}

	/**
	 * Gets the trusted root CA certificate.
	 * 
	 * @return The certificate.
	 */
	public static X509Certificate getTrustedRootCA() {
		return rootCaCertificate;
	}

	/**
	 * @return a certificate without digitalSignature in keyusage extension
	 */
	public static X509Certificate getNoSigningCertificate() {
		return nosigningCertificate;
	}
}
