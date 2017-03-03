/*
 * Copyright © 2017 Jelurida IP B.V.
 *
 * See the LICENSE.txt file at the top-level directory of this distribution
 * for licensing information.
 *
 * Unless otherwise agreed in a custom licensing agreement with Jelurida B.V.,
 * no part of the Nxt software, including this file, may be copied, modified,
 * propagated, or distributed except according to the terms contained in the
 * LICENSE.txt file.
 *
 *
 * Removal or modification of this copyright notice is prohibited.
 */
package nxt.authentication;

import java.nio.ByteBuffer;

/**
 * Security token
 */
public interface SecurityToken {

    /**
     * Get the security token expiration
     *
     * @return                  Expiration time or zero if the token does not expire
     */
    public int getTokenExpiration();

    /**
     * Get peer account identifier
     *
     * @return                  Peer account identifier
     */
    public long getPeerAccountId();

    /**
     * Get peer account public key
     *
     * @return                  Peer account public key
     */
    public byte[] getPeerAccountPublicKey();

    /**
     * Get peer announced address
     *
     * @return                  Peer announced address or null if there is no announced address
     */
    public String getPeerAccountAddress();

    /**
     * Get the session key
     *
     * @return                  Session key or null if there is no session key
     */
    public byte[] getSessionKey();

    /**
     * Get the serialized token length
     *
     * @return                  Serialized token length
     */
    public int getLength();

    /**
     * Get the serialized token
     *
     * @return                  Serialized token
     */
    public byte[] getBytes();

    /**
     * Add the serialized token to a buffer
     *
     * @param   buffer          Byte buffer
     * @return                  Byte buffer
     */
    public default ByteBuffer getBytes(ByteBuffer buffer) {
        buffer.put(getBytes());
        return buffer;
    }
}
