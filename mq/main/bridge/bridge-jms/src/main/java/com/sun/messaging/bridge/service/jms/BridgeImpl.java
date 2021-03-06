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

package com.sun.messaging.bridge.service.jms;

import java.util.ArrayList;
import java.util.Properties;
import java.util.Iterator;
import java.util.ResourceBundle;
import java.util.concurrent.RejectedExecutionException;
import org.jvnet.hk2.annotations.Service;
import org.glassfish.hk2.api.PerLookup;
import com.sun.messaging.jmq.io.Status;
import com.sun.messaging.bridge.api.Bridge;
import com.sun.messaging.bridge.api.BridgeContext;
import com.sun.messaging.bridge.api.BridgeException;
import com.sun.messaging.bridge.api.BridgeCmdSharedResources;
import com.sun.messaging.bridge.api.BridgeCmdSharedReplyData;
import com.sun.messaging.bridge.api.JMSBridgeStore;

/**
 * 
 * @author amyk
 *
 */
@Service(name = Bridge.JMS_TYPE)
@PerLookup
public class BridgeImpl implements Bridge, AsyncStartListener {
    
    private final String _type = Bridge.JMS_TYPE;
    private String _name = null ;

    private State _state = State.STOPPED;
    private JMSBridge _jmsbridge = null;;

    public BridgeImpl() {};
 
    public synchronized void asyncStartCompleted() throws Exception {
        if (_state == State.STARTING) {
            _state = State.STARTED;
            return;
        }
        throw new IllegalStateException(
        "Received bridge async start completion notification on unexpected state "+
        _state.toString(JMSBridge.getJMSBridgeResources()));
    }

    public synchronized void asyncStartFailed() throws Exception {
        if (_state == State.STARTING) {
            _state = State.STOPPED;
            return;
        }
        throw new IllegalStateException(
        "Received bridge async start failure notification on unexpected state "+
        _state.toString(JMSBridge.getJMSBridgeResources()));
    }

    /**
     * Start the bridge
     *
     * @param bc the bridge context
     * @param args start parameters 
     *
     * @return true if successfully started; false if started asynchronously
     *
     * @throws Exception if unable to start the bridge
     */
    public synchronized boolean start(BridgeContext bc, String[] args) throws Exception {

        String linkName = parseLinkName(args);
        if (linkName != null) {
            if (_state != State.STARTED) {
               throw new IllegalStateException(
               JMSBridge.getJMSBridgeResources().getKString(
               JMSBridge.getJMSBridgeResources().X_LINKOP_ALLOWED_STATE, 
                   State.STARTED.toString(JMSBridge.getJMSBridgeResources())));
            }
            return _jmsbridge.start(linkName, null);
        }

        if (_state == State.STARTED) {
            return true;
        }
        if (_state == State.STARTING) {
            return false;
        }

        State oldstate = _state;
        _state = State.STARTING;
        boolean inited = false;
        try {
             _jmsbridge = new JMSBridge();
             _jmsbridge.init(bc, _name, parseResetArg(args));
             inited = true;
             try {
                 if (_jmsbridge.start(parseLinkName(args), this)) {
                     _state = State.STARTED;
                     return true;
                 } else {
                     return false;
                 }
             } catch (RejectedExecutionException e) {
                 _state = oldstate;
                 throw e;
             }
        } catch (Exception e) {
            try {
                stop(bc, null);
            } catch (Throwable t) {}
            if (!inited) {
                _jmsbridge = null;
                throw e;
            }
            throw new BridgeException(e.getMessage(), e, Status.CREATED);
        }
    }

    /**
     * Pause the bridge
     *
     * @param bc the bridge context
     * @param args pause parameters 
     *
     * @throws Exception if unable to pause the bridge
     */
    public synchronized void pause(BridgeContext bc, String[] args) throws Exception {

        String linkName = parseLinkName(args);
        if (linkName != null) {
            if (_state != State.STARTED) {
               throw new IllegalStateException(
               JMSBridge.getJMSBridgeResources().getKString(
               JMSBridge.getJMSBridgeResources().X_LINKOP_ALLOWED_STATE, 
                   State.STARTED.toString(JMSBridge.getJMSBridgeResources())));
            }
            _jmsbridge.pause(linkName);
            return;
        }

        if (_state == State.PAUSED) return; 
        if (_state != State.STARTED) {
            throw new IllegalStateException(
               JMSBridge.getJMSBridgeResources().getKString(
               JMSBridge.getJMSBridgeResources().X_PAUSE_NOT_ALLOWED_STATE,
                         _state.toString(JMSBridge.getJMSBridgeResources())));
        }

        State oldstate = _state;
        _state = State.PAUSING;
        try {
            _jmsbridge.pause(parseLinkName(args));
            _state = State.PAUSED;
        } catch (Exception e) {
            if (e instanceof RejectedExecutionException) {
                _state = oldstate;
                throw e;
            }
            try {
            _jmsbridge.stop(parseLinkName(args)); 
            } catch (Throwable t) {};
            throw e;
        }
    }

    /**
     * Resume the bridge
     *
     * @param bc the bridge context
     * @param args resume parameters 
     *
     * @throws Exception if unable to resume the bridge
     */
    public synchronized void resume(BridgeContext bc, String[] args) throws Exception {

        String linkName = parseLinkName(args);
        if (linkName != null) {
            if (_state != State.STARTED) {
               throw new IllegalStateException(
               JMSBridge.getJMSBridgeResources().getKString(
               JMSBridge.getJMSBridgeResources().X_LINKOP_ALLOWED_STATE, 
                   State.STARTED.toString(JMSBridge.getJMSBridgeResources())));
            }
            _jmsbridge.resume(linkName);
            return;
        }

        if (_state == State.STARTED) return; 
        if (_state != State.PAUSED) {
            throw new IllegalStateException(
               JMSBridge.getJMSBridgeResources().getKString(
               JMSBridge.getJMSBridgeResources().X_RESUME_NOT_ALLOWED_STATE, 
                   _state.toString(JMSBridge.getJMSBridgeResources())));
        }

        State oldstate = _state;
        _state = State.RESUMING;
        try {
            _jmsbridge.resume(parseLinkName(args));
        } catch (Exception e) {
            if (e instanceof RejectedExecutionException) {
                _state = oldstate;
                throw e;
            }
            try {
            _jmsbridge.stop(parseLinkName(args));
            } catch (Throwable t) {}
            throw e;
        }
        _state = State.STARTED;
    }

    /**
     * Stop the bridge
     *
     * @param bc the bridge context
     * @param args stop parameters 
     *
     * @throws Exception if unable to stop the bridge
     */
    public synchronized void stop(BridgeContext bc, String[] args) throws Exception {
        if (_jmsbridge == null) {
            _state = State.STOPPED;
            throw new IllegalStateException(
               JMSBridge.getJMSBridgeResources().getKString(
               JMSBridge.getJMSBridgeResources().X_BRIDGE_NOT_INITED, getName()));
        }

        String linkName = parseLinkName(args);
        if (linkName != null) {
            if (_state != State.STARTED) {
               throw new IllegalStateException(
               JMSBridge.getJMSBridgeResources().getKString(
               JMSBridge.getJMSBridgeResources().X_LINKOP_ALLOWED_STATE, 
                   State.STARTED.toString(JMSBridge.getJMSBridgeResources())));
            }
            _jmsbridge.stop(linkName);
            return;
        }

        if (_state == State.STOPPED) return;

        State oldstate = _state;
        _state = State.STOPPING;
        try { 
            _jmsbridge.stop(parseLinkName(args));
            _state = State.STOPPED;
        } catch (RejectedExecutionException e) {
           _state = oldstate;
           throw e;
        }
    }

    private synchronized JMSBridge getJMSBridge() {
        return _jmsbridge;
    }

    /**
     * List the bridge
     *
     * @param bc the bridge context
     * @param args list parameters 
     * @param rb ResourceBundle to get String resources for data
     *
     * @throws Exception if unable to stop the bridge
     */
    public ArrayList<BridgeCmdSharedReplyData> list(
                                     BridgeContext bc,
                                     String[] args,
                                     ResourceBundle rb) 
                                     throws Exception {
        JMSBridge jb = getJMSBridge();
        if (jb == null) {
            throw new IllegalStateException(
               JMSBridge.getJMSBridgeResources().getKString(
               JMSBridge.getJMSBridgeResources().X_BRIDGE_NOT_INITED, getName()));
        }
        
        ArrayList<BridgeCmdSharedReplyData> replys = new ArrayList<BridgeCmdSharedReplyData>();

        BridgeCmdSharedReplyData reply = new BridgeCmdSharedReplyData(4, 3, "-");

        String oneRow[] = new String [4];
        oneRow[0] = rb.getString(BridgeCmdSharedResources.I_BGMGR_TITLE_BRIDGE_NAME);
        oneRow[1] = rb.getString(BridgeCmdSharedResources.I_BGMGR_TITLE_BRIDGE_TYPE);
        oneRow[2] = rb.getString(BridgeCmdSharedResources.I_BGMGR_TITLE_BRIDGE_STATE);
        oneRow[3] = rb.getString(BridgeCmdSharedResources.I_BGMGR_TITLE_NUM_LINKS);
        reply.addTitle(oneRow);

        oneRow[0] = getName();
        oneRow[1] = getType();
        oneRow[2] = getState().toString(rb);
        oneRow[3] = String.valueOf(jb.getNumLinks());
        reply.add(oneRow);

        replys.add(reply);

        String linkName = parseLinkName(args);
        boolean debugMode = parseDebugModeArg(args);
        ArrayList<BridgeCmdSharedReplyData> rep = jb.list(linkName, rb, debugMode);
        replys.addAll(rep);

        return replys;
    }

    /**
     *
     * @return the type of the bridge
     */
    public String getType() {
        return _type;
    }

    /**
     *
     * @return true if multiple of this type of bridge can coexist
     */
    public boolean isMultipliable() {
        return true;
    }


    /**
     * Set the bridge's name
     */
    public void setName(String name) {
        _name = name;
    }

    /**
     *
     * @return the bridge's name
     */
    public String getName() {
        return _name;
    }


    public String toString() {
        return _name+"["+getState()+"]";
    }

    /**
     *
     * @return a string representing the bridge's status (length <= 15, uppercase)
     */
    public synchronized State getState() {
        return _state;
    }

    /**
     *
     * @return an object of exported service corresponding to the className
     */
    public Object getExportedService(Class c, Properties props) throws Exception {

        if (c == null) throw new IllegalArgumentException("null class");
        if (props == null) throw new IllegalArgumentException("null props");

        if (!c.getName().equals(JMSBridgeStore.class.getName())) {
            throw new IllegalArgumentException("Unexpected class "+c);
        }
        return JMSBridge.exportJMSBridgeStoreService(props);
    }

    /**
     * The passed args must ensure correct options
     */
    private String parseLinkName(String[] args) {
        if (args == null) return null;

        for (int n = 0; n < args.length; n++) {
            if (args[n].equals("-ln")) return args[++n];
        }

        return null;
    }

    /**
     */
    private boolean parseResetArg(String[] args) {
        if (args == null) return false;

        for (int n = 0; n < args.length; n++) {
            if (args[n].equals("-reset")) return true;
        }

        return false;
    }

    private boolean parseDebugModeArg(String[] args) {
        if (args == null) return false;

        for (int n = 0; n < args.length; n++) {
            if (args[n].equals("-debug")) return true;
        }

        return false;
    }

}
