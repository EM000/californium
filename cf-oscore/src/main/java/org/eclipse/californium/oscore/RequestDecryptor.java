/*******************************************************************************
 * Copyright (c) 2019 RISE SICS and others.
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
 *    Joakim Brorsson
 *    Ludwig Seitz (RISE SICS)
 *    Tobias Andersson (RISE SICS)
 *    Rikard Höglund (RISE SICS)
 *    
 ******************************************************************************/
package org.eclipse.californium.oscore;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.network.serialization.DataParser;
import org.eclipse.californium.cose.Encrypt0Message;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.CoAP.Code;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.elements.util.DatagramReader;
import org.eclipse.californium.oscore.OSCoreCtx;
import org.eclipse.californium.oscore.OSCoreCtxDB;
import org.eclipse.californium.oscore.OptionJuggle;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.eclipse.californium.cose.HeaderKeys;

/**
 * 
 * Decrypts an OSCORE encrypted Request.
 *
 */
public class RequestDecryptor extends Decryptor {

	/**
	 * The logger
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(RequestDecryptor.class.getName());

	/**
	 * @param db the context database used
	 * @param request the request to decrypt
	 * 
	 * @return the decrypted request
	 * 
	 * @throws CoapOSException if decryption fails
	 */
	public static Request decrypt(OSCoreCtxDB db, Request request) throws CoapOSException {
		
		LOGGER.info("Removes E options from outer options which are not allowed there");
		discardEOptions(request);

		byte[] protectedData = request.getPayload();
		Encrypt0Message enc;
		OptionSet uOptions = request.getOptions();
		try {
			enc = decompression(protectedData, request);
		} catch (OSException e) {
			LOGGER.error(ErrorDescriptions.FAILED_TO_DECODE_COSE);
			throw new CoapOSException(ErrorDescriptions.FAILED_TO_DECODE_COSE, ResponseCode.BAD_OPTION);
		}

		CBORObject kid = enc.findAttribute(HeaderKeys.KID);
		if (kid == null || !kid.getType().equals(CBORType.ByteString)) {
			LOGGER.error(ErrorDescriptions.MISSING_KID);
			throw new CoapOSException(ErrorDescriptions.FAILED_TO_DECODE_COSE, ResponseCode.BAD_OPTION);
		}
		byte[] rid = kid.GetByteString();

		//Retrieve Context ID (kid context)
		CBORObject kidContext = enc.findAttribute(CBORObject.FromObject(10));
		byte[] contextID = null;
		if (kidContext != null) {
			contextID = kidContext.GetByteString();
		}

		//Trigger context re-derivation if applicable
		checkContextRederivation(db, rid, contextID);

		OSCoreCtx ctx = db.getContext(rid);

		if (ctx == null) {
			LOGGER.error(ErrorDescriptions.CONTEXT_NOT_FOUND);
			throw new CoapOSException(ErrorDescriptions.CONTEXT_NOT_FOUND, ResponseCode.UNAUTHORIZED);
		}

		byte[] plaintext;
		try {
			plaintext = decryptAndDecode(enc, request, ctx, null);
		} catch (OSException e) {
			//First check for replay exceptions
			if (e.getMessage().equals(ErrorDescriptions.REPLAY_DETECT)) { 
				LOGGER.error(ErrorDescriptions.REPLAY_DETECT);
				throw new CoapOSException(ErrorDescriptions.REPLAY_DETECT, ResponseCode.UNAUTHORIZED);
			}
			//Otherwise return generic error message
			LOGGER.error(ErrorDescriptions.DECRYPTION_FAILED);
			throw new CoapOSException(ErrorDescriptions.DECRYPTION_FAILED, ResponseCode.BAD_REQUEST);
		}
		
		//Check if parsing of request plaintext succeeds
		try {
			DatagramReader reader = new DatagramReader(new ByteArrayInputStream(plaintext));
			ctx.setCoAPCode(Code.valueOf(reader.read(CoAP.MessageFormat.CODE_BITS)));
			// resets option so eOptions gets priority during parse
			request.setOptions(EMPTY);
			DataParser.parseOptionsAndPayload(reader, request);
		} catch (Exception e) {
			LOGGER.error(ErrorDescriptions.DECRYPTION_FAILED);
			throw new CoapOSException(ErrorDescriptions.DECRYPTION_FAILED, ResponseCode.BAD_REQUEST);
		}
			
		OptionSet eOptions = request.getOptions();
		eOptions = OptionJuggle.merge(eOptions, uOptions);	
		request.setOptions(eOptions);

		// We need the kid value on layer level
		request.getOptions().setOscore(rid);

		//Set information about the OSCORE context used in the endpoint context of this request
		OSCoreEndpointContextInfo.receivingRequest(ctx, request);

		return OptionJuggle.setRealCodeRequest(request, ctx.getCoAPCode());
	}

	/**
	 * Checks if an incoming messages should trigger re-derivation of the security
	 * context as detailed in Appendix B.2. If so this re-derivation is also performed.
	 *
	 * See https://tools.ietf.org/html/draft-ietf-core-object-security-16#section-5.2
	 *
	 * @param db the context database used
	 * @param rid the recipient ID
	 * @param receivedContextID the previously received context ID
	 *
	 * @throws CoapOSException if re-generation of the context fails
	 */
	private static void checkContextRederivation(OSCoreCtxDB db, byte[] rid, byte[] receivedContextID) throws CoapOSException {
		//Get the context corresponding to the incoming rid
		OSCoreCtx ctx = db.getContext(rid);

		//Check if the received Context ID matches the one in the context, if so do nothing
		if (receivedContextID == null || Arrays.equals(receivedContextID, ctx.getIdContext())) {
			return;
		}

		//Otherwise generate a new context with the newly received Context ID
		OSCoreCtx newCtx = null;
		try {
			newCtx = new OSCoreCtx(ctx.getMasterSecret(), true, ctx.getAlg(),
					ctx.getSenderId(), ctx.getRecipientId(), ctx.getKdf(),
					ctx.getRecipientReplaySize(), ctx.getSalt(), receivedContextID);
		} catch (OSException e) {
			LOGGER.error(ErrorDescriptions.CONTEXT_REGENERATION_FAILED);
			throw new CoapOSException(ErrorDescriptions.CONTEXT_REGENERATION_FAILED, ResponseCode.BAD_REQUEST);
		}
		newCtx.setIncludeContextId(true);

		//Now replace the old context with the newly generated in the context DB
		db.addContext(newCtx);
	}
}
