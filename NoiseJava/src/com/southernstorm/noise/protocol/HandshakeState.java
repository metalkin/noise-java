/*
 * Copyright (C) 2016 Southern Storm Software, Pty Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.southernstorm.noise.protocol;

import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.ShortBufferException;

/**
 * Interface to a Noise handshake.
 */
public class HandshakeState implements Destroyable {

	private SymmetricState symmetric;
	private boolean isInitiator;
	private DHState localKeyPair;
	private DHState localEphemeral;
	private DHState remotePublicKey;
	private DHState remoteEphemeral;
	private DHState fixedEphemeral;
	private int action;
	private int requirements;
	private byte[] pattern;
	private int patternIndex;
	private byte[] preSharedKey;
	private byte[] prologue;

	/**
	 * Enumerated value that indicates that the handshake object
	 * is handling the initiator role.
	 */
	public static final int INITIATOR = 1;

	/**
	 * Enumerated value that indicates that the handshake object
	 * is handling the responder role.
	 */
	public static final int RESPONDER = 2;

	/**
	 * No action is required of the application yet because the
	 * handshake has not started.
	 */
	public static final int NO_ACTION = 0;
	
	/**
	 * The HandshakeState expects the application to write the
	 * next message payload for the handshake.
	 */
	public static final int WRITE_MESSAGE = 1;
	
	/**
	 * The HandshakeState expects the application to read the
	 * next message payload from the handshake.
	 */
	public static final int READ_MESSAGE = 2;
	
	/**
	 * The handshake has failed due to some kind of error.
	 */
	public static final int FAILED = 3;
	
	/**
	 * The handshake is over and the application is expected to call
	 * split() and begin data session communications.
	 */
	public static final int SPLIT = 4;

	/**
	 * The handshake is complete and the data session ciphers
	 * have been split() out successfully.
	 */
	public static final int COMPLETE = 5;
	
	/**
	 * Local static keypair is required for the handshake.
	 */
	private static final int LOCAL_REQUIRED = 0x01;
	
	/**
	 * Remote static keypai is required for the handshake.
	 */
	private static final int REMOTE_REQUIRED = 0x02;
	
	/**
	 * Pre-shared key is required for the handshake.
	 */
	private static final int PSK_REQUIRED = 0x04;
	
	/**
	 * Ephemeral key for fallback pre-message has been provided.
	 */
	private static final int FALLBACK_PREMSG = 0x08;
	
	/**
	 * The local public key is part of the pre-message.
	 */
	private static final int LOCAL_PREMSG = 0x10;

	/**
	 * The remote public key is part of the pre-message.
	 */
	private static final int REMOTE_PREMSG = 0x20;

	/**
	 * Creates a new Noise handshake.
	 * 
	 * @param protocolName The name of the Noise protocol.
	 * @param role The role, HandshakeState.INITIATOR or HandshakeState.RESPONDER.
	 * 
	 * @throws IllegalArgumentException The protocolName is not
	 * formatted correctly, or the role is not recognized.
	 * 
	 * @throws NoSuchAlgorithmException One of the cryptographic algorithms
	 * that is specified in the protocolName is not supported.
	 */
	public HandshakeState(String protocolName, int role) throws NoSuchAlgorithmException
	{
		// Parse the protocol name into its components.
		String[] components = protocolName.split("_");
		if (components.length != 5)
			throw new IllegalArgumentException("Protocol name must have 5 components");
		String prefix = components[0];
		String patternId = components[1];
		String dh = components[2];
		String cipher = components[3];
		String hash = components[4];
		if (!prefix.equals("Noise") && !prefix.equals("NoisePSK"))
			throw new IllegalArgumentException("Prefix must be Noise or NoisePSK");
		pattern = Pattern.lookup(patternId);
		if (pattern == null)
			throw new IllegalArgumentException("Handshake pattern is not recognized");
		byte flags = pattern[0];
		if (role == RESPONDER) {
			// Reverse the pattern flags so that the responder is "local".
			flags = Pattern.reverseFlags(flags);
		}

		// Check that the role is correctly specified.
		if (role != INITIATOR && role != RESPONDER)
			throw new IllegalArgumentException("Role must be initiator or responder");

		// Initialize this object.  This will also create the cipher and hash objects.
		symmetric = new SymmetricState(protocolName, cipher, hash);
		isInitiator = (role == INITIATOR);
		action = NO_ACTION;
		requirements = computeRequirements(flags, prefix, role, false);
		patternIndex = 1;
		
		// Create the DH objects that we will need later.
		if ((flags & Pattern.FLAG_LOCAL_STATIC) != 0)
			localKeyPair = Noise.createDH(dh);
		if ((flags & Pattern.FLAG_LOCAL_EPHEMERAL) != 0)
			localEphemeral = Noise.createDH(dh);
		if ((flags & Pattern.FLAG_REMOTE_STATIC) != 0)
			remotePublicKey = Noise.createDH(dh);
		if ((flags & Pattern.FLAG_REMOTE_EPHEMERAL) != 0)
			remoteEphemeral = Noise.createDH(dh);
	}

	/**
	 * Gets the name of the Noise protocol. 
	 *
	 * @return The protocol name.
	 */
	public String getProtocolName()
	{
		return symmetric.getProtocolName();
	}
	
	/**
	 * Gets the role for this handshake.
	 * 
	 * @return The role, HandshakeState.INITIATOR or HandshakeState.RESPONDER.
	 */
	public int getRole()
	{
		return isInitiator ? INITIATOR : RESPONDER;
	}

	/**
	 * Determine if this handshake needs a pre-shared key value
	 * and one has not been configured yet.
	 * 
	 * @return true if a pre-shared key is needed; false if not.
	 */
	public boolean needsPreSharedKey()
	{
		if (preSharedKey != null)
			return false;
		else
			return (requirements & PSK_REQUIRED) != 0;
	}
	
	/**
	 * Determine if this object has already been configured with a
	 * pre-shared key.
	 * 
	 * @return true if the pre-shared key has already been configured;
	 * false if one is not needed or it has not been configured yet.
	 */
	public boolean hasPreSharedKey()
	{
		return preSharedKey != null;
	}

	/**
	 * Sets the pre-shared key for this handshake.
	 * 
	 * @param key Buffer containing the pre-shared key value.
	 * @param offset Offset into the buffer of the first byte of the key.
	 * @param length The length of the pre-shared key, which must be 32.
	 * 
	 * @throws IllegalArgumentException The length is not 32.
	 * 
	 * @throws UnsupportedOperationException Pre-shared keys are not
	 * supported for this handshake type.
	 * 
	 * @throws IllegalStateException The handshake has already started,
	 * so the pre-shared key can no longer be set.
	 */
	public void setPreSharedKey(byte[] key, int offset, int length)
	{
		if (length != 32) {
			throw new IllegalArgumentException
				("Pre-shared keys must be 32 bytes in length");
		}
		if ((requirements & PSK_REQUIRED) == 0) {
			throw new UnsupportedOperationException
				("Pre-shared keys are not supported for this handshake");
		}
		if (action != NO_ACTION) {
			throw new IllegalStateException
				("Handshake has already started; cannot set pre-shared key");
		}
		if (preSharedKey != null) {
			Noise.destroy(preSharedKey);
			preSharedKey = null;
		}
		preSharedKey = Noise.copySubArray(key, offset, length);
	}
	
	/**
	 * Sets the prologue for this handshake.
	 * 
	 * @param prologue Buffer containing the prologue value.
	 * @param offset Offset into the buffer of the first byte of the prologue.
	 * @param length The length of the prologue in bytes.
	 * 
	 * @throws IllegalStateException The handshake has already started,
	 * so the prologue can no longer be set.
	 */
	public void setPrologue(byte[] prologue, int offset, int length)
	{
		if (action != NO_ACTION) {
			throw new IllegalStateException
				("Handshake has already started; cannot set prologue");
		}
		if (this.prologue != null) {
			Noise.destroy(this.prologue);
			this.prologue = null;
		}
		this.prologue = Noise.copySubArray(prologue, offset, length);
	}
	
	/**
	 * Gets the keypair object for the local static key.
	 * 
	 * @return The keypair, or null if a local static key is not required.
	 */
	public DHState getLocalKeyPair()
	{
		return localKeyPair;
	}

	/**
	 * Determine if this handshake requires a local static key.
	 * 
	 * @return true if a local static key is needed; false if not.
	 * 
	 * If the local static key has already been set, then this function
	 * will return false.
	 */
	public boolean needsLocalKeyPair()
	{
		if (localKeyPair != null)
			return !localKeyPair.hasPrivateKey();
		else
			return false;
	}

	/**
	 * Determine if this handshake has already been configured
	 * with a local static key.
	 * 
	 * @return true if the local static key has been configured;
	 * false if not.
	 */
	public boolean hasLocalKeyPair()
	{
		if (localKeyPair != null)
			return localKeyPair.hasPrivateKey();
		else
			return false;
	}
	
	/**
	 * Gets the public key object for the remote static key.
	 * 
	 * @return The public key, or null if a remote static key
	 * is not required.
	 */
	public DHState getRemotePublicKey()
	{
		return remotePublicKey;
	}
	
	/**
	 * Determine if this handshake requires a remote static key.
	 * 
	 * @return true if a remote static key is needed; false if not.
	 * 
	 * If the remote static key has already been set, then this function
	 * will return false.
	 */
	public boolean needsRemotePublicKey()
	{
		if (remotePublicKey != null)
			return !remotePublicKey.hasPublicKey();
		else
			return false;
	}
	
	/**
	 * Determine if this handshake has already been configured
	 * with a remote static key.
	 * 
	 * @return true if the remote static key has been configured;
	 * false if not.
	 */
	public boolean hasRemotePublicKey()
	{
		if (remotePublicKey != null)
			return remotePublicKey.hasPublicKey();
		else
			return false;
	}

	/**
	 * Gets the DHState object containing a fixed local ephemeral
	 * key value for this handshake.
	 * 
	 * @return The fixed ephemeral key object, or null if a local
	 * ephemeral key is not required by this handshake.
	 * 
	 * This function is intended for testing only.  It can be used
	 * to establish a fixed ephemeral key for test vectors.  This
	 * function should not be used in real applications.
	 */
	public DHState getFixedEphemeralKey()
	{
		if (fixedEphemeral != null)
			return fixedEphemeral;
		if (localEphemeral == null)
			return null;
		try {
			fixedEphemeral = Noise.createDH(localEphemeral.getDHName());
		} catch (NoSuchAlgorithmException e) {
			// This shouldn't happen - the local ephemeral key would
			// have already been created with the same name!
			fixedEphemeral = null;
		}
		return fixedEphemeral;
	}

	// Empty value for when the prologue is not supplied.
	private static final byte[] emptyPrologue = new byte [0];

	/**
	 * Starts the handshake running.
	 * 
	 * This function is called after all of the handshake parameters have been
	 * provided to the HandshakeState object.  This function should be followed
	 * by calls to writeMessage() or readMessage() to process the handshake
	 * messages.  The getAction() function indicates the action to take next.
	 * 
	 * @throws IllegalStateException The handshake has already started, or one or
	 * more of the required parameters has not been supplied.
	 * 
	 * @throws UnsupportedOperationException An attempt was made to start a
	 * "XXfallback" handshake pattern without first calling fallback() on a
	 * previous "IK" handshake.
	 * 
	 * @see getAction(), writeMessage(), readMessage(), fallback()
	 */
	public void start()
	{
		if (action != NO_ACTION) {
			throw new IllegalStateException
				("Handshake has already started; cannot start again");
		}
		if (pattern == Pattern.noise_pattern_XXfallback &&
				(requirements & FALLBACK_PREMSG) == 0) {
			throw new UnsupportedOperationException
				("Cannot start XXfallback pattern");
		}
		
		// Check that we have satisfied all of the pattern requirements.
		if ((requirements & LOCAL_REQUIRED) != 0) {
			if (!localKeyPair.hasPrivateKey())
				throw new IllegalStateException("Local static key required");
		}
		if ((requirements & REMOTE_REQUIRED) != 0) {
			if (!remotePublicKey.hasPublicKey())
				throw new IllegalStateException("Remote static key required");
		}
		if ((requirements & PSK_REQUIRED) != 0) {
			if (preSharedKey != null)
				throw new IllegalStateException("Pre-shared key required");
		}
		
		// Hash the prologue value.
		if (prologue != null)
			symmetric.mixHash(prologue, 0, prologue.length);
		else
			symmetric.mixHash(emptyPrologue, 0, 0);
		
		// Hash the pre-shared key into the chaining key and handshake hash.
		if (preSharedKey != null)
			symmetric.mixPreSharedKey(preSharedKey);
		
		// Mix the pre-supplied public keys into the handshake hash.
		if (isInitiator) {
			if ((requirements & LOCAL_PREMSG) != 0)
				symmetric.mixPublicKey(localKeyPair);
			if ((requirements & REMOTE_PREMSG) != 0)
				symmetric.mixPublicKey(remotePublicKey);
			if ((requirements & FALLBACK_PREMSG) != 0)
				symmetric.mixPublicKey(remoteEphemeral);
		} else {
			if ((requirements & REMOTE_PREMSG) != 0)
				symmetric.mixPublicKey(remotePublicKey);
			if ((requirements & LOCAL_PREMSG) != 0)
				symmetric.mixPublicKey(localKeyPair);
			if ((requirements & FALLBACK_PREMSG) != 0)
				symmetric.mixPublicKey(localEphemeral);
		}
		
		// The handshake has official started - set the first action.
		if (isInitiator)
			action = WRITE_MESSAGE;
		else
			action = READ_MESSAGE;
	}

	/**
	 * Falls back to the "XXfallback" handshake pattern.
	 * 
	 * This function is used to help implement the "Noise Pipes" protocol.
	 * It resets a HandshakeState object with the handshake pattern "IK",
	 * converting it into an object with the handshake pattern "XXfallback".
	 * Information from the previous session such as the local keypair,
	 * the initiator's ephemeral key, the prologue value, and the
	 * pre-shared key, are passed to the new session.
	 *
	 * Once the fallback has been initiated, the application can set
	 * new values for the handshake parameters if the values from the
	 * previous session do not apply.  For example, the application may
	 * use a different prologue for the fallback than for the original
	 * session.
	 *
	 * After setting any new parameters, the application calls start()
	 * again to restart the handshake from where it left off before
	 * the fallback.
	 *
	 * Note that this function reverses the roles of initiator and responder.
	 * 
	 * @throws UnsupportedOperationException The handshake pattern is
	 * not currently set to "IK".
	 * 
	 * @throws IllegalStateException The previous protocol has not started
	 * or it has not reached the fallback position yet.
	 * 
	 * @throws NoSuchAlgorithmException One of the cryptographic algorithms
	 * that is specified in the new protocolName is not supported.
	 * 
	 * @see start()
	 */
	public void fallback() throws NoSuchAlgorithmException
	{
		// Bail out if the previous pattern is not "IK".
		if (pattern != Pattern.noise_pattern_IK)
			throw new UnsupportedOperationException("Previous handshake pattern is not IK");

	    // The initiator should be waiting for a return message from the
	    // responder, and the responder should have failed on the first
	    // handshake message from the initiator.  We also allow the
	    // responder to fallback after processing the first message
	    // successfully; it decides to always fall back anyway.
		if (isInitiator) {
			if ((action != FAILED && action != READ_MESSAGE) || !localEphemeral.hasPublicKey())
				throw new IllegalStateException("Initiator cannot fall back from this state");
		} else {
			if ((action != FAILED && action != WRITE_MESSAGE) || !remoteEphemeral.hasPublicKey())
				throw new IllegalStateException("Responder cannot fall back from this state");
		}
		
		// Format a new protocol name for the "XXfallback" variant
		// and recreate the SymmetricState object.
		String name = symmetric.getProtocolName().replace("_IK_", "_XXfallback_");
		String[] components = name.split("_");
		SymmetricState newSymmetric = new SymmetricState(name, components[3], components[4]);
		symmetric.destroy();
		symmetric = newSymmetric;
		
		// Convert the HandshakeState to the "XXfallback" pattern.
		remotePublicKey.clearKey();
		if (isInitiator) {
			remoteEphemeral.clearKey();
			isInitiator = false;
		} else {
			localEphemeral.clearKey();
			isInitiator = true;
		}
		action = NO_ACTION;
		pattern = Pattern.noise_pattern_XXfallback;
		patternIndex = 1;
		byte flags = pattern[0];
		if (!isInitiator) {
			// Reverse the pattern flags so that the responder is "local".
			flags = Pattern.reverseFlags(flags);
		}
		requirements = computeRequirements(flags, components[0], isInitiator ? INITIATOR : RESPONDER, false);
	}

	/**
	 * Gets the next action that the application should perform for
	 * the handshake part of the protocol.
	 * 
	 * @return One of HandshakeState.NO_ACTION, HandshakeState.WRITE_MESSAGE,
	 * HandshakeState.READ_MESSAGE, HandshakeState.SPLIT, or
	 * HandshakeState.FAILED.
	 */
	public int getAction()
	{
		return action;
	}

	/**
	 * Mixes the result of a Diffie-Hellman calculation into the chaining key.
	 * 
	 * @param local Local private key object.
	 * @param remote Remote public key object.
	 */
	private void mixDH(DHState local, DHState remote)
	{
		if (local == null || remote == null)
			throw new IllegalStateException("Pattern definition error");
		int len = local.getSharedKeyLength();
		byte[] shared = new byte [len];
		try {
			local.calculate(shared, 0, remote);
			symmetric.mixKey(shared, 0, len);
		} finally {
			Noise.destroy(shared);
		}
	}

	/**
	 * Writes a message payload during the handshake.
	 * 
	 * @param message The buffer that will be populated with the
	 * handshake packet to be written to the transport.
	 * @param messageOffset First offset within the message buffer
	 * to be populated.
	 * @param payload Buffer containing the payload to add to the
	 * handshake message; can be null if there is no payload.
	 * @param payloadOffset Offset into the payload buffer of the
	 * first payload buffer.
	 * @param payloadLength Length of the payload in bytes.
	 * 
	 * @return The length of the data written to the message buffer.
	 * 
	 * @throws IllegalStateException The action is not WRITE_MESSAGE.
	 * 
	 * @throws IllegalArgumentException The payload is null, but
	 * payloadOffset or payloadLength is non-zero.
	 * 
	 * @throws ShortBufferException The message buffer does not have
	 * enough space for the handshake message. 
	 * 
	 * @see getAction(), readMessage()
	 */
	public int writeMessage(byte[] message, int messageOffset, byte[] payload, int payloadOffset, int payloadLength) throws ShortBufferException
	{
		int messagePosn = messageOffset;
		boolean success = false;

		// Validate the parameters and state.
		if (action != WRITE_MESSAGE) {
			throw new IllegalStateException
				("Handshake state does not allow writing messages");
		}
		if (payload == null && (payloadOffset != 0 || payloadLength != 0)) {
			throw new IllegalArgumentException("Invalid payload argument");
		}
		if (messageOffset > message.length) {
			throw new ShortBufferException();
		}
		
		// Format the message.
		try {
			// Process tokens until the direction changes or the patten ends.
			for (;;) {
				if (patternIndex >= pattern.length) {
					// The pattern has finished, so the next action is "split".
					action = SPLIT;
					break;
				}
				byte token = pattern[patternIndex++];
				if (token == Pattern.FLIP_DIR) {
					// Change directions, so this message is complete and the
					// next action is "read message".
					action = READ_MESSAGE;
					break;
				}
				int space = message.length - messagePosn;
				int len, macLen;
				switch (token) {
					case Pattern.E:
					{
			            // Generate a local ephemeral keypair and add the public
			            // key to the message.  If we are running fixed vector tests,
			            // then the ephemeral key may have already been provided.
						if (localEphemeral == null)
							throw new IllegalStateException("Pattern definition error");
						if (fixedEphemeral == null)
							localEphemeral.generateKeyPair();
						else
							localEphemeral.copyFrom(fixedEphemeral);
						len = localEphemeral.getPublicKeyLength();
						if (space < len)
							throw new ShortBufferException();
						localEphemeral.getPublicKey(message, messagePosn);
						symmetric.mixHash(message, messagePosn, len);

						// If the protocol is using pre-shared keys, then also mix
			            // the local ephemeral key into the chaining key.
						if (preSharedKey != null)
							symmetric.mixKey(message, messagePosn, len);
						messagePosn += len;
					}
					break;

					case Pattern.S:
					{
			            // Encrypt the local static public key and add it to the message.
						if (localKeyPair == null)
							throw new IllegalStateException("Pattern definition error");
						len = localKeyPair.getPublicKeyLength();
						macLen = symmetric.getMACLength();
						if (space < (len + macLen))
							throw new ShortBufferException();
						localKeyPair.getPublicKey(message, messagePosn);
						messagePosn += symmetric.encryptAndHash(message, messagePosn, message, messagePosn, len);
					}
					break;

					case Pattern.DHEE:
					{
						// DH operation with local and remote ephemeral keys.
						mixDH(localEphemeral, remoteEphemeral);
					}
					break;

					case Pattern.DHES:
					{
						// DH operation with local ephemeral and remote static keys.
						mixDH(localEphemeral, remotePublicKey);
					}
					break;

					case Pattern.DHSE:
					{
						// DH operation with local static and remote ephemeral keys.
						mixDH(localKeyPair, remoteEphemeral);
					}
					break;

					case Pattern.DHSS:
					{
						// DH operation with local and remote static keys.
						mixDH(localKeyPair, remotePublicKey);
					}
					break;
					
					default:
					{
						// Unknown token code.  Abort.
						throw new IllegalStateException("Unknown handshake token " + Integer.toString(token));
					}
				}
			}
			
			// Add the payload to the message buffer and encrypt it.
			if (payload != null)
				messagePosn += symmetric.encryptAndHash(payload, payloadOffset, message, messagePosn, payloadLength);
			else
				messagePosn += symmetric.encryptAndHash(message, messagePosn, message, messagePosn, 0);
			success = true;
		} finally {
			// If we failed, then clear any sensitive data that may have
			// already been written to the message buffer.
			if (!success) {
				Arrays.fill(message, messageOffset, message.length - messageOffset, (byte)0);
				action = FAILED;
			}
		}
		return messagePosn - messageOffset;
	}

	/**
	 * Reads a message payload during the handshake.
	 * 
	 * @param message Buffer containing the incoming handshake
	 * that was read from the transport.
	 * @param messageOffset Offset of the first message byte.
	 * @param messageLength The length of the incoming message.
	 * @param payload Buffer that will be populated with the message payload.
	 * @param payloadOffset Offset of the first byte in the
	 * payload buffer to be populated with payload data.
	 * 
	 * @return The length of the payload.
	 * 
	 * @throws IllegalStateException The action is not READ_MESSAGE.
	 * 
	 * @throws ShortBufferException The message buffer does not have
	 * sufficient bytes for a valid message or the payload buffer does
	 * not have enough space for the decrypted payload. 
	 * 
	 * @throws AEADBadTagException A MAC value in the message failed
	 * to verify.
	 */
	public int readMessage(byte[] message, int messageOffset, int messageLength, byte[] payload, int payloadOffset) throws ShortBufferException, AEADBadTagException
	{
		boolean success = false;
		int messageEnd = messageOffset + messageLength;
		
		// Validate the parameters.
		if (action != READ_MESSAGE) {
			throw new IllegalStateException
				("Handshake state does not allow reading messages");
		}
		if (messageOffset > message.length || payloadOffset > payload.length) {
			throw new ShortBufferException();
		}
		if (messageLength > (message.length - messageOffset)) {
			throw new ShortBufferException();
		}
		
		// Process the message.
		try {
			// Process tokens until the direction changes or the patten ends.
			for (;;) {
				if (patternIndex >= pattern.length) {
					// The pattern has finished, so the next action is "split".
					action = SPLIT;
					break;
				}
				byte token = pattern[patternIndex++];
				if (token == Pattern.FLIP_DIR) {
					// Change directions, so this message is complete and the
					// next action is "write message".
					action = WRITE_MESSAGE;
					break;
				}
				int space = messageEnd - messageOffset;
				int len, macLen;
				switch (token) {
					case Pattern.E:
					{
			            // Save the remote ephemeral key and hash it.
						if (remoteEphemeral == null)
							throw new IllegalStateException("Pattern definition error");
						len = remoteEphemeral.getPublicKeyLength();
						if (space < len)
							throw new ShortBufferException();
						symmetric.mixHash(message, messageOffset, len);
						remoteEphemeral.setPublicKey(message, messageOffset);
						if (remoteEphemeral.isNullPublicKey()) {
			                // The remote ephemeral key is null, which means that it is
			                // not contributing anything to the security of the session
			                // and is in fact downgrading the security to "none at all"
			                // in some of the message patterns.  Reject all such keys.
							throw new AEADBadTagException("Null remote public key");
						}

						// If the protocol is using pre-shared keys, then also mix
			            // the remote ephemeral key into the chaining key.
						if (preSharedKey != null)
							symmetric.mixKey(message, messageOffset, len);
						messageOffset += len;
					}
					break;
	
					case Pattern.S:
					{
						// Decrypt and read the remote static key.
						if (remotePublicKey == null)
							throw new IllegalStateException("Pattern definition error");
						len = remotePublicKey.getPublicKeyLength();
						macLen = symmetric.getMACLength();
						if (space < (len + macLen))
							throw new ShortBufferException();
						byte[] temp = new byte [len];
						try {
							if (symmetric.decryptAndHash(message, messageOffset, temp, 0, len + macLen) != len)
								throw new ShortBufferException();
							remotePublicKey.setPublicKey(temp, 0);
						} finally {
							Noise.destroy(temp);
						}
						messageOffset += len + macLen;
					}
					break;
	
					case Pattern.DHEE:
					{
						// DH operation with local and remote ephemeral keys.
						mixDH(localEphemeral, remoteEphemeral);
					}
					break;
	
					case Pattern.DHES:
					{
						// DH operation with local static and remote ephemeral keys.
						mixDH(localKeyPair, remoteEphemeral);
					}
					break;
	
					case Pattern.DHSE:
					{
						// DH operation with local ephemeral and remote static keys.
						mixDH(localEphemeral, remotePublicKey);
					}
					break;
	
					case Pattern.DHSS:
					{
						// DH operation with local and remote static keys.
						mixDH(localKeyPair, remotePublicKey);
					}
					break;
					
					default:
					{
						// Unknown token code.  Abort.
						throw new IllegalStateException("Unknown handshake token " + Integer.toString(token));
					}
				}
			}
			
			// Decrypt the message payload.
			int payloadLength = symmetric.decryptAndHash(message, messageOffset, payload, payloadOffset, messageEnd - messageOffset);
			success = true;
			return payloadLength;
		} finally {
			// If we failed, then clear any sensitive data that may have
			// already been written to the payload buffer.
			if (!success) {
				Arrays.fill(payload, payloadOffset, payload.length - payloadOffset, (byte)0);
				action = FAILED;
			}
		}
	}

	/**
	 * Splits the transport encryption CipherState objects out of
	 * this HandshakeState object once the handshake completes.
	 * 
	 * @return The pair of ciphers for sending and receiving.
	 * 
	 * @throws IllegalStateException The action is not SPLIT.
	 */
	public CipherStatePair split()
	{
		if (action != SPLIT) {
			throw new IllegalStateException
				("Handshake has not finished");
		}
		CipherStatePair pair = symmetric.split();
		if (!isInitiator)
			pair.swap();
		action = COMPLETE;
		return pair;
	}

	/**
	 * Splits the transport encryption CipherState objects out of
	 * this HandshakeObject after mixing in a secondary symmetric key.
	 * 
	 * @param secondaryKey The buffer containing the secondary key.
	 * @param offset The offset of the first secondary key byte.
	 * @param length The length of the secondary key in bytes, which
	 * must be either 0 or 32.
	 * @return The pair of ciphers for sending and receiving.
	 * 
	 * @throws IllegalStateException The action is not SPLIT.
	 * 
	 * @throws IllegalArgumentException The length is not 0 or 32.
	 */
	public CipherStatePair split(byte[] secondaryKey, int offset, int length)
	{
		if (action != SPLIT) {
			throw new IllegalStateException
				("Handshake has not finished");
		}
		CipherStatePair pair = symmetric.split(secondaryKey, offset, length);
		if (!isInitiator) {
			// Swap the sender and receiver objects for the responder
			// to make it easier on the application to know which is which.
			pair.swap();
		}
		action = COMPLETE;
		return pair;
	}
	
	/**
	 * Gets the current value of the handshake hash.
	 * 
	 * @return The handshake hash.  This must not be modified by the caller.
	 * 
	 * The handshake hash value is only of use to the application after
	 * split() has been called.
	 * 
	 * @throws IllegalStateException The action is not COMPLETE.
	 */
	public byte[] getHandshakeHash()
	{
		if (action != COMPLETE) {
			throw new IllegalStateException
				("Handshake has not completed");
		}
		return symmetric.getHandshakeHash();
	}

	@Override
	public void destroy() {
		if (symmetric != null)
			symmetric.destroy();
		if (localKeyPair != null)
			localKeyPair.destroy();
		if (localEphemeral != null)
			localEphemeral.destroy();
		if (remotePublicKey != null)
			remotePublicKey.destroy();
		if (remoteEphemeral != null)
			remoteEphemeral.destroy();
		if (fixedEphemeral != null)
			fixedEphemeral.destroy();
		if (preSharedKey != null)
			Noise.destroy(preSharedKey);
		if (prologue != null)
			Noise.destroy(prologue);
	}
	
	/**
	 * Computes the requirements for a handshake.
	 * 
	 * @param flags The flags from the handshake's pattern.
	 * @param prefix The prefix from the protocol name; typically
	 * "Noise" or "NoisePSK".
	 * @param role The role, HandshakeState.INITIATOR or HandshakeState.RESPONDER.
	 * @param isFallback Set to true if we need the requirements for a
	 * fallback pattern; false for a regular pattern.
	 * 
	 * @return The set of requirements for the handshake.
	 */
	private static int computeRequirements(byte flags, String prefix, int role, boolean isFallback)
	{
		int requirements = 0;
	    if ((flags & Pattern.FLAG_LOCAL_STATIC) != 0) {
	        requirements |= LOCAL_REQUIRED;
	    }
	    if ((flags & Pattern.FLAG_LOCAL_REQUIRED) != 0) {
	        requirements |= LOCAL_REQUIRED;
	        requirements |= LOCAL_PREMSG;
	    }
	    if ((flags & Pattern.FLAG_REMOTE_REQUIRED) != 0) {
	        requirements |= REMOTE_REQUIRED;
	        requirements |= REMOTE_PREMSG;
	    }
	    if ((flags & (Pattern.FLAG_REMOTE_EPHEM_REQ |
	    		      Pattern.FLAG_LOCAL_EPHEM_REQ)) != 0) {
	        if (isFallback)
	            requirements |= FALLBACK_PREMSG;
	    }
	    if (prefix.equals("NoisePSK")) {
	        requirements |= PSK_REQUIRED;
	    }
	    return requirements;
	}
}
