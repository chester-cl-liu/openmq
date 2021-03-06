/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2017 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://oss.oracle.com/licenses/CDDL+GPL-1.1
 * or LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 */ 

package com.sun.messaging.jmq.jmsserver.multibroker.raptor.handlers;

import java.io.*;
import com.sun.messaging.jmq.util.*;
import com.sun.messaging.jmq.jmsserver.util.*;
import com.sun.messaging.jmq.io.*;
import com.sun.messaging.jmq.jmsserver.core.*;
import com.sun.messaging.jmq.jmsserver.multibroker.raptor.*;

public class TransferFileRequestHandler extends GPacketHandler {
    private static boolean DEBUG = false;

    public TransferFileRequestHandler(RaptorProtocol p) {
        super(p);
    }

    public void handle(BrokerAddress sender, GPacket pkt) {
        if (DEBUG)
            logger.log(logger.DEBUG, "TransferFileRequestHandler");

        if (pkt.getType() == ProtocolGlobals.G_TRANSFER_FILE_REQUEST) {
            handleTransferFileRequest(sender, pkt);
        } else if (pkt.getType() == ProtocolGlobals.G_TRANSFER_FILE_REQUEST_REPLY) {
            handleTransferFileRequestReply(sender, pkt);
        } else {
            logger.log(logger.WARNING, "TakeoverMEHandler " +
                "Internal error : Cannot handle this packet :" +
                pkt.toLongString());
        }
    }

    private void handleTransferFileRequest(BrokerAddress sender, GPacket pkt) {
        int status = Status.OK;
        String reason = null;
        try {
            p.receivedTransferFileRequest(sender, pkt);
        } catch (Exception e) {
            status = Status.ERROR;
            reason = e.getMessage();
            if (!(e instanceof BrokerException)) {
                String[] args = new String[] {
                    ProtocolGlobals.getPacketTypeDisplayString(pkt.getType()),
                    sender.toString(), e.toString() };
                reason = br.getKString(
                    br.E_CLUSTER_PROCESS_PACKET_FROM_BROKER_FAIL, args);
                logger.log(logger.ERROR, reason);
            }
        }
        ClusterTransferFileRequestInfo tfr = ClusterTransferFileRequestInfo.newInstance(pkt);
        try {
            c.unicast(sender, tfr.getReplyGPacket(status, reason));
        } catch (IOException e) {
            Object args = new Object[] { ProtocolGlobals.getPacketTypeDisplayString(
                                         ProtocolGlobals.G_TRANSFER_FILE_REQUEST_REPLY),
                                         sender, tfr.toString() };
            logger.logStack(logger.ERROR, br.getKString(
                br.E_CLUSTER_SEND_PACKET_FAILED, args), e);
        }
    }

    private void handleTransferFileRequestReply(BrokerAddress sender, GPacket pkt) {
        p.receivedTransferFileRequestReply(sender, pkt);
    }
}

/*
 * EOF
 */
