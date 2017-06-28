/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
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

package com.sun.messaging.jms.ra;


import javax.jms.*;
import javax.naming.NamingException;
import javax.resource.NotSupportedException;
import javax.resource.ResourceException;
import javax.resource.spi.InvalidPropertyException;
import javax.resource.spi.endpoint.MessageEndpointFactory;

import com.sun.messaging.ConnectionConfiguration;
import com.sun.messaging.jmq.ClientConstants;
import com.sun.messaging.jmq.DestinationName;

import com.sun.messaging.jmq.jmsservice.JMSService;
import com.sun.messaging.jmq.jmsservice.JMSService.SessionAckMode;
import com.sun.messaging.jms.ra.util.CustomTokenizer;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

import java.lang.reflect.Method;
import com.sun.messaging.jms.ra.api.JMSRAEndpointConsumer;

/**
 *  Encapsulates a message consumer for the Oracle GlassFish(tm) Server Message Queue J2EE Resource Adapter.
 */

public class EndpointConsumer implements
    javax.jms.ExceptionListener,
    com.sun.messaging.jms.notification.EventListener,
    JMSRAEndpointConsumer
{
    private static final String QUEUE = "javax.jms.Queue";
    private static final String TOPIC = "javax.jms.Topic";

    /** Resource Adapter holding this epConsumer */
    protected com.sun.messaging.jms.ra.ResourceAdapter ra = null;

    /** XAConnectionFactory for this endpoint (not used for RADirect) */
    private com.sun.messaging.XAConnectionFactory xacf = null;
    /** DirectConnectionFactory for this endpoint (used for RADirect) */
    protected DirectConnectionFactory dcf = null;

    /** The Consumer ID associated with this epConsumer */
    private int cID = 0;

    /** The MessageEndpointFactory ID associated with this epConsumer */
    private int fID = 0;

    /** The destination type associated with this epConsumer */
    private int destinationType = ClientConstants.DESTINATION_TYPE_UNKNOWN;

    /**
     *  onMessage delivery related parameters
     */
    protected boolean isDeliveryTransacted = false;
    protected boolean noAckDelivery = false;
    protected Method onMessageMethod = null;


    /** The subscription durability associated with this epConsumer */
    protected boolean isDurable = false;
    protected String clientId = null;
    protected String mName = null;

    /** Flags whether this epConsumer is transcated */
    ////////private boolean transactedDelivery = false;

    /** Flags Deactivation */
    protected boolean deactivated = false;

    /** Connection and Session held by this epConsumer */
    protected com.sun.messaging.jmq.jmsclient.XAConnectionImpl xac = null;
    protected com.sun.messaging.jmq.jmsclient.XASessionImpl xas = null;
    
    private DirectConnection dc = null;
    private DirectSession ds = null;

    /** MessageListener for this epConsumer */
    protected MessageListener msgListener = null;

    /** MessageConsumer for this epConsumer */
    protected MessageConsumer msgConsumer = null;
    protected MessageConsumer msgConsumer2 = null;

    /** Destination for this epConsumer */
    protected Destination destination = null;

    /** whether this epConsumer is operating in RA direct mode or not */
    protected boolean useRADirect = false;

    protected ActivationSpec aSpec = null;
    protected MessageEndpointFactory endpointFactory = null;

    protected String username = null; // username only used for direct mode 
    protected String password = null; // password only used for direct mode 
    protected String selector = null;
    protected String subscriptionName = null;
    protected int exRedeliveryAttempts = 0;
    protected int exRedeliveryInterval = 0;

    private boolean stopping = false;

    private boolean logRCFailures = true;
    private int maxLoopDelay = 120000;

    /* Loggers */
    private static transient final String _className =
            "com.sun.messaging.jms.ra.EndpointConsumer";
    protected static transient final String _lgrNameInboundMessage =
            "javax.resourceadapter.mqjmsra.inbound.message";
    protected static transient final Logger _loggerIM =
            Logger.getLogger(_lgrNameInboundMessage);
    protected static transient final String _lgrMIDPrefix = "MQJMSRA_EC";
    protected static transient final String _lgrMID_EET = _lgrMIDPrefix + "1001: ";
    protected static transient final String _lgrMID_INF = _lgrMIDPrefix + "1101: ";
    protected static transient final String _lgrMID_WRN = _lgrMIDPrefix + "2001: ";
    protected static transient final String _lgrMID_ERR = _lgrMIDPrefix + "3001: ";
    protected static transient final String _lgrMID_EXC = _lgrMIDPrefix + "4001: ";

    // Mark no-arg constructor private to ensure that it is not used
    @SuppressWarnings("unused")
	private EndpointConsumer() {
    }

    /** Create an EndpointConsumer for Direct mode */
    public EndpointConsumer(com.sun.messaging.jms.ra.ResourceAdapter ra,
            MessageEndpointFactory endpointFactory,
            javax.resource.spi.ActivationSpec spec)
    throws ResourceException {
        if (ra == null || endpointFactory == null || spec ==null){
            throw new NotSupportedException("MQRA:EC:const:null RA||EPF||AS");
        }
        if (!(spec instanceof com.sun.messaging.jms.ra.ActivationSpec)) {
            throw new NotSupportedException("MQRA:EC:const:" +
                    "Unsupported ActivationSpec Class-" +
                    spec.getClass());
        }
        this.aSpec = (ActivationSpec)spec;
        this.endpointFactory = endpointFactory;
        this.ra = ra;
       
        _init();
    }

    protected void _init()
    throws ResourceException {
        if (!this.ra.getInAppClientContainer()) {
            AccessController.doPrivileged(
                new PrivilegedAction<Object>()
                {
                    public Object run() {
                        System.setProperty("imq.DaemonThreads", "true");
                        return null;
                    }
                }
            );
            //System.setProperty("imq.DaemonThreads", "true");
        }
        
        // ask the activation spec whether this endpoint should use RADirect
        // it will return true if the RA is configured to use RADirect and we haven't overridden addressList in the activation spec
        useRADirect = this.aSpec.useRADirect();

        Object cfObj = null;
        String connectionFactoryLookup = aSpec.getConnectionFactoryLookup();
        if (connectionFactoryLookup != null) {
            try {
                cfObj = Util.jndiLookup(connectionFactoryLookup);
            } catch (NamingException e) {
                String errorMessage = "MQRA:EC:Invalid connectionFactoryLookup " + connectionFactoryLookup + " configured in ActivationSpec of MDB for no JNDI name found";
                throw new ResourceException(errorMessage, e);
            }
        }

		// Configure connection factory
		if (useRADirect) {
			JMSService jmsservice = this.ra._getJMSService();
			this.dcf = new com.sun.messaging.jms.ra.DirectConnectionFactory(jmsservice, null);

            if (cfObj != null) {
                DirectConnectionFactory cfd = (DirectConnectionFactory) cfObj;
                ManagedConnectionFactory mcf = cfd.getMCF();
                cfd.setMCF(mcf);
                aSpec.setMCF(mcf);
            }
            this.username = this.aSpec.getUserName();
            this.password = this.aSpec.getPassword();
		} else {
			xacf = new com.sun.messaging.XAConnectionFactory();
			try {
                if (cfObj != null) {
                    ConnectionFactoryAdapter cfa = (ConnectionFactoryAdapter) cfObj;
                    ManagedConnectionFactory mcf = cfa.getMCF();
                    aSpec.setMCF(mcf);
                }

				// get addressList from activation spec or (suitably adjusted) from the resource adapter
				this.xacf.setProperty(ConnectionConfiguration.imqAddressList, aSpec._AddressList());

				// get addressListBehavior from activation spec or resource adapter
				xacf.setProperty(ConnectionConfiguration.imqAddressListBehavior, aSpec.getAddressListBehavior());
				
				// get username from activation spec or resource adapter
				this.xacf.setProperty(ConnectionConfiguration.imqDefaultUsername, aSpec.getUserName());

				// get password from activation spec or resource adapter
				this.xacf.setProperty(ConnectionConfiguration.imqDefaultPassword, aSpec.getPassword());

				// get addressListIterations from activation spec or resource adapter
				xacf.setProperty(ConnectionConfiguration.imqAddressListIterations, Integer.toString(aSpec.getAddressListIterations()));
				
				// get reconnectAttempts from activation spec or resource adapter
				this.xacf.setProperty(ConnectionConfiguration.imqReconnectAttempts, Integer.toString(aSpec.getReconnectAttempts()));

				// get reconnectEnabled from activation spec 
				this.xacf.setProperty(ConnectionConfiguration.imqReconnectEnabled, Boolean.toString(aSpec.getReconnectEnabled()));

				// get reconnectInterval from activation spec or resource adapter
				this.xacf.setProperty(ConnectionConfiguration.imqReconnectInterval, Integer.toString(aSpec.getReconnectInterval()));
				
				// configure xacf with any additional connection factory properties defined in the activation spec
				setAdditionalConnectionFactoryProperties(aSpec.getOptions());

			} catch (JMSException jmse) {
				System.err.println("MQRA:EC:constr:Exception setting connection factory properties: "
						+ jmse.getMessage());
			}
		}
        
        
        this.onMessageMethod = this.ra._getOnMessageMethod();
        this.exRedeliveryAttempts = this.aSpec.getEndpointExceptionRedeliveryAttempts();
        this.exRedeliveryInterval = this.aSpec.getEndpointExceptionRedeliveryInterval();
        this.mName = this.aSpec.getMdbName();
        this.selector = this.aSpec.getMessageSelector();
        this.subscriptionName = this.aSpec.getSubscriptionName();
        
        String cId = this.aSpec.getClientId();
        if ((cId != null) && !("".equals(cId)) && (cId.length() > 0)){
            this.clientId = cId;
        } else {
            this.clientId = null;
        }
        try {
            this.isDeliveryTransacted = this.endpointFactory.isDeliveryTransacted(this.onMessageMethod);
        } catch (NoSuchMethodException ex) {
            //Assume delivery is non-transacted
            //Fix to throw NotSupportedException on activation
            //ex.printStackTrace();
        }
        setDestinationType();
        if (this.destinationType == ClientConstants.DESTINATION_TYPE_TOPIC) {
            //Will throw NotSupportedException if clientId not set properly
            setIsDurable();
        }

        checkSubscriptionScopeAndClientId();
        if (this.isDurable) {
            if (!aSpec.isUseSharedSubscriptionInClusteredContainer())
                throw new NotSupportedException("MQRA:EC:Error:Must not set useSharedSubscriptionInClusteredContainer flag for durable subscriptions");

            if (this.clientId == null && aSpec.getSubscriptionScope() == null) {
                // automatically generate client identifier
                if (aSpec._getGroupName() != null) {
                    this.clientId = aSpec._getGroupName() + "{m:" + mName + "}";
                } else {
                    this.clientId = "{m:" + mName + "}";
                }
            }
        } else {
            if ((aSpec._isNoAckDeliverySet()) && 
                (this.destination instanceof com.sun.messaging.Topic) &&
                (!this.isDeliveryTransacted)) {
                this.noAckDelivery = true;
            }
            if (aSpec._isInClusteredContainerSet() && aSpec.isUseSharedSubscriptionInClusteredContainer()) {
                if (this.clientId == null && aSpec.getSubscriptionScope() == null) {
                    if ((mName == null) || ("".equals(mName))) {
                        throw new NotSupportedException(
                            "MQRA:EC:Error:Clustered Message Consumer requires"+
                                " non-null clientID OR mdbName:" +
                                "clientID="+this.clientId+":mdbName="+mName);
                    } else {
                        //set effective clientId from mName
                        if (aSpec._getGroupName() != null) {
                            this.clientId = aSpec._getGroupName()+"{m:"+mName+"}";
                        } else {
                            this.clientId = "{m:"+mName+"}";
                        }

                    }
                }
            }
        }
       
        if (this.useRADirect){
            this.createDirectMessageConsumer();
        } else {
        	this.createRemoteMessageConsumer();
        }
    }

    //javax.jms.ExceptionListener interface method
    public void onException(JMSException exception)
    {
        _loggerIM.severe(_lgrMID_EXC+"onException:"+exception.getMessage());
        //System.err.println("MQRA:EC:EL:Got Connection Exception:"+exception.getMessage());
        logRCFailures = true;
        if (msgListener != null) {
            //System.err.println("MQRA:EC:EL:invalidatingOMRs");
            msgListener.invalidateOnMessageRunners();
        }
        int loopDelay = aSpec.getReconnectInterval();
        int loopCount = 0;
        while (!stopping) {
            //wait till initial interval expires
            try {
                Thread.sleep(loopDelay);
            } catch (Exception ie) {
            }
            try {
                loopCount += 1;
                if (logRCFailures) {
                    _loggerIM.severe(_lgrMID_EXC+"onException:"+aSpec.toString());
                    //System.err.println("MQRA:EC:EL:"+aSpec.toString());
                }
                _loggerIM.severe(_lgrMID_EXC+"onException:reconnect attempt loop# "+loopCount+" :Delayed "+loopDelay+" milliseconds.");
                //System.err.println("MQRA:EC:EL:addressList reconnect attempt_loop#"+loopCount+":Delayed "+loopDelay+" milliseconds.");
                synchronized (this) {
                    if (!stopping) {
                        createRemoteMessageConsumer();
                        _loggerIM.severe(_lgrMID_EXC+"onException:reconnect success on loop# "+loopCount+" for "+aSpec.toString());
                        //System.err.println("MQRA:EC:EL:RE-CONNECTED consumer:on loop#"+loopCount+" for "+aSpec.toString());
                    }
                }
                break;
            } catch (Exception e) {
                if (logRCFailures) {
                    _loggerIM.severe(_lgrMID_EXC+"onException:Unable to re-establish connection for "+aSpec.toString()+"\nin "+ra.toString());
                    //System.err.println("MQRA:EC:EL:Exception SEVERE:Unable to re-establish connection for "+aSpec.toString()+"\n"+ra.toString());
                } else {
                    logRCFailures = false;
                }
                if (loopDelay < maxLoopDelay) {
                    loopDelay *= 3;
                    if (loopDelay > maxLoopDelay) loopDelay = maxLoopDelay;
                }
            }
        }
    }

    //com.sun.messaging.jms.notification.EventListener interface method
    public void
    onEvent(com.sun.messaging.jms.notification.Event evnt)
    {
        _loggerIM.entering(_className, "onEvent()", evnt);
        _loggerIM.info(_lgrMID_INF+"onEvent:Connection Event:"+evnt.toString());
    }


    // Public Methods
    //

    /** Returns the ResourceAdapter associated with this
     *  EndpointConsumer
     *
     *  @return The ResourceAdapter
     */
    public com.sun.messaging.jms.ra.ResourceAdapter
    getResourceAdapter()
    {
        return ra;
    }

    /** Returns the consumerID associated with this
     *  EndpointConsumer
     *
     *  @return The consumerID
     */
    public int
    getConsumerID()
    {
        return cID;
    }

    /* Returns the factoryID for the MessageFactory
     * associated with this EndpointConsumer
     *
     *  @return The factoryID
     */  
    public int
    getFactoryID()
    {
        return fID;
    }

    /** Returns the MessageEndpointFactory associated with this
     *  EndpointConsumer
     *
     *  @return The MessageEndpointFactory
     */
    public MessageEndpointFactory
    getMessageEndpointFactory()
    {
        //System.out.println("MQRA:EC:getMsgEpFctry:fID="+fID);
        MessageEndpointFactory epf = ra._getMessageFactory(fID);
        return epf;
    }

    /** Returns the XASession associated with this
     *  EndpointConsumer
     *
     *  @return The XASession
     */
    public javax.jms.XASession
    getXASession()
    {
        return xas;
    }

    public DirectSession getDirectSession(){
        return this.ds;
    }

    /** Sets this EndpointConsumer to Deactivated
     *
     */
    public void
    setDeactivated()
    {
        deactivated = true;
    }

    /** Creates a MessageConsumer associated with this EndpointConsumer
     *  after validating the passed in ActivationSpec parameter.
     *  The MessageConsumer delivers the messages to a MessageEndpoint
     *  manufactured  using the MessageEndpointFactory passed in.
     * 
     *  @param endpointFactory The MessageEndpointFactory used to create a
     *         MessageEndpoint to which messages will be delivered.
     *  @param spec The ActivationSpec instance. This must be an instance
     *         of the MQ RA Activation spec implementation class.
     */  
	public void createRemoteMessageConsumer() throws ResourceException {
  	
		try {
			xac = (com.sun.messaging.jmq.jmsclient.XAConnectionImpl) xacf.createXAConnection();
			if (xac == null)  // This should never happen
			    throw new ResourceException("MQRA:EC:Error:createRemoteMessageConsumer failed: cannot create XAConnection");

			if ((aSpec._isInClusteredContainerSet()) && aSpec.isUseSharedSubscriptionInClusteredContainer()) {
				xac.setRANamespaceUID(aSpec._getRAUID());
			}
			_loggerIM.fine("MQRA:EC:createRemoteMessageConsumer setting clientID to " + this.clientId);
			if (this.clientId != null) {
				xac.setClientID(this.clientId);
			}
			xac.setExceptionListener(this);
			((com.sun.messaging.jms.Connection) xac).setEventListener(this);
		} catch (JMSException jmse) {
			if (xac != null) {
				try {
					xac.close();
				} catch (JMSException jmsecc) {
				}
				xac = null;
			}

			if (logRCFailures) {
				jmse.printStackTrace();
			}
			NotSupportedException nse = new NotSupportedException(
					"MQRA:EC:Error:createRemoteMessageConsumer failed:aborting due to:" + jmse.getMessage());
			nse.initCause(jmse);
			throw nse;
		}

        
        // success
        try {
            if (this.isDurable) {
                this.xas = (com.sun.messaging.jmq.jmsclient.XASessionImpl)
                        xac.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            } else {
                if (noAckDelivery) {
                    this.xas = (com.sun.messaging.jmq.jmsclient.XASessionImpl)
                        xac.createSession(
                            com.sun.messaging.jms.Session.NO_ACKNOWLEDGE);
                } else {
                    this.xas = (com.sun.messaging.jmq.jmsclient.XASessionImpl)
                        xac.createSession(false, Session.CLIENT_ACKNOWLEDGE);
                }
            }
            ((com.sun.messaging.jmq.jmsclient.XASessionImpl)xas)._setRAEndpointSession();

            if (aSpec.getSubscriptionScope() != null) {
                subscriptionName = getSubscriptionName();
                if (this.isDurable){
	                msgConsumer = xas.createSharedDurableConsumer((Topic)destination,
	                        subscriptionName,
	                        aSpec.getMessageSelector());
                } else {
                    msgConsumer = xas.createSharedConsumer((Topic)destination,
                            subscriptionName,
                            aSpec.getMessageSelector());
                }
            } else {
                if (this.isDurable) {
                    msgConsumer = xas.createDurableSubscriber((Topic)destination,
                            aSpec.getSubscriptionName(),
                            aSpec.getMessageSelector(), false);
                } else {
                    msgConsumer = xas.createConsumer(destination, aSpec.getMessageSelector());
                    //test to see if Queue is enabled for more than one consumer when InClustered true
                    if (destination instanceof javax.jms.Queue && aSpec._isInClusteredContainerSet()) {
                        //Fail activation if it is not
                        try {
                            msgConsumer2 = xas.createConsumer(destination, aSpec.getMessageSelector());
                            msgConsumer2.close();
                            msgConsumer2 = null;
                        } catch (JMSException jmse) {
                            try {
                                xac.close();
                            } catch (JMSException jmsecc) {
                                //System.out.println("MQRA:EC:closed xac on conn creation exception-"+jmsecc.getMessage());
                            }
                            xac = null;

                            NotSupportedException nse = new NotSupportedException(
                                   "MQRA:EC:Error clustering multiple consumers on Queue:\n"+jmse.getMessage());
                            nse.initCause(jmse);
                            throw nse;
                        }
                    }
                }
            }
            msgListener = new MessageListener(this, this.endpointFactory, aSpec);
            //System.out.println("MQRA:EC:Created msgListener");
            //msgConsumer.setMessageListener(new MessageListener(this, epFactory, spec));
            msgConsumer.setMessageListener(msgListener);
            //System.out.println("MQRA:EC:Set msgListener");
            //System.out.println("MQRA:EC:Starting Connection");
            xac.start();
            updateFactoryConsumerTables(this.endpointFactory, aSpec);

        }  catch (JMSException jmse) {
            if (xac != null) {
                try {
                    xac.close();
                } catch (JMSException jmsecc) {
                    //System.out.println("MQRA:EC:closed xac on conn creation exception-"+jmsecc.getMessage());
                }
                xac = null;
            }
            NotSupportedException nse = new NotSupportedException(
                    "MQRA:EC:Error creating Remote Message Consumer:\n"+jmse.getMessage());
            nse.initCause(jmse);
            throw nse;
        }
    }

    /**
     * Set additional arbitrary connection factory properties
     * 
     * The properties must be specified as a String containing a comma-separated list of name=value pairs
     * e.g. prop1=value1,prop2=value2
     * If a value contains a = or , you can either 
     * place the whole value between quotes (prop1="val=ue") or 
     * use \ as an escape character (prop1=val\,ue)
     * 
     * This method cannot be used to set properties which are configured internally or which have their own setter methods. These are:
     * imqReconnectEnabled, imqReconnectAttempts, imqReconnectInterval, imqDefaultUsername, 
     * imqDefaultPassword, imqAddressList, imqAddressListIterations, imqAddressListBehavior
     * 
     * Any values specified for those properties will be ignored
     *
     * @param props connection factory properties as a comma-separated list of name=value pairs
     */
	private void setAdditionalConnectionFactoryProperties(String stringProps) {
		if (stringProps==null) return;
		Map<String, String> originalAdditionalCFProperties = new HashMap<String, String>();

    	Hashtable <String, String> props=null;
    	try {
			props=CustomTokenizer.parseToProperties(stringProps);
		} catch (InvalidPropertyException ipe) {
			// syntax error in properties
            IllegalArgumentException iae = new IllegalArgumentException(_lgrMID_EXC+"Invalid value for activation spec property options: " + stringProps); 
            iae.initCause(ipe);
            throw iae;
		}
		
		for (Enumeration<String> keysEnum = props.keys(); keysEnum.hasMoreElements();) {
			String thisPropertyName = (String) keysEnum.nextElement();
			
			// don't allow properties that are, or can be, set elsewhere to be overridden here as this might have unexpected results
			if (thisPropertyName.equals(ConnectionConfiguration.imqReconnectEnabled) ||
				thisPropertyName.equals(ConnectionConfiguration.imqReconnectInterval) ||
				thisPropertyName.equals(ConnectionConfiguration.imqDefaultUsername) ||
				thisPropertyName.equals(ConnectionConfiguration.imqDefaultPassword) ||
				thisPropertyName.equals(ConnectionConfiguration.imqAddressList) ||
				thisPropertyName.equals(ConnectionConfiguration.imqAddressListIterations) ||
				thisPropertyName.equals(ConnectionConfiguration.imqAddressListBehavior) ||
				thisPropertyName.equals(ConnectionConfiguration.imqReconnectAttempts)) {
				_loggerIM.warning(_lgrMID_WRN+"Cannot use activation spec property options to set property "+thisPropertyName+": ignoring");
				continue;
			} 
			
			try {
				originalAdditionalCFProperties.put(thisPropertyName,xacf.getProperty(thisPropertyName));
				xacf.setProperty(thisPropertyName, props.get(thisPropertyName));
			} catch (JMSException e) {
				IllegalArgumentException iae = new IllegalArgumentException(_lgrMID_EXC+"Error setting connection factory property "+thisPropertyName+" (defined in activation spec property options) to "+props.get(thisPropertyName));
				iae.initCause(e);
				throw iae;
			}
		}		
	}
	
	protected void startMessageConsumer()
    throws Exception {
        
    }

    /**
     *  Stop a consumer and connections associated with this EndpointConsumer
     */  
    public void stopMessageConsumer()
    throws Exception
    {
        if (this.useRADirect){
            stopDirectMessageConsumer();
        } else {
            stopRemoteMessageConsumer();
        }
    }
    
    /** Stops a consumer and connections associated with this EndpointConsumer
     */  
    public void stopRemoteMessageConsumer()
    throws Exception
    {
        stopping = true;
        synchronized (this) {
        com.sun.messaging.jmq.jmsclient.SessionImpl mqsess;
        //System.out.println("MQRA:EC:stopMessageConsumer()");
        if (msgConsumer != null) {
            try {
                if (msgListener != null) {
                    mqsess = ((com.sun.messaging.jmq.jmsclient.SessionImpl)xas);
                    //System.out.println("MQRA:EC:stopMessageConsumer:_stopFromRA:sessionId="+mqsess.getBrokerSessionID());
                    mqsess._stopFromRA();
                    //System.out.println("MQRA:EC:stopMessageConsumer:_stopFromRA-done/wfOMRs");
                    msgListener.waitForAllOnMessageRunners();
                    //System.out.println("MQRA:EC:stopMessageConsumer:wfOMRs-done/releaseOMRs");
                    msgListener.releaseOnMessageRunners();
                    //System.out.println("MQRA:EC:stopMessageConsumer:Done releasing OMRs/session.close()");
                    xas.close();
                    //System.out.println("MQRA:EC:stopMessageConsumer:Done session.close()");
                }
                //System.out.println("MQRA:EC:stopMessageConsumer:_stopFromRA");
                ////////<---((com.sun.messaging.jmq.jmsclient.SessionImpl)xas)._stopFromRA();
                //System.out.println("MQRA:EC:stopMessageConsumer:done _stopFromRA");
                //System.out.println("MQRA:EC:stopMessageConsumer:closing msgConsumer");
                ///////////////msgConsumer.close();
                //System.out.println("MQRA:EC:stopMessageConsumer:closed msgConsumer...........................");
            } catch (JMSException jmse) {
                ResourceException re = new ResourceException("MQRA:EC:Error on closing MessageConsumer");
                re.initCause(jmse);
                throw re;
            }
        }
        if (xac != null) {
            try {
                xac.close();
            } catch (JMSException jmse) {
                ResourceException re = new ResourceException("MQRA:EC:Error closing JMS Connection");
                re.initCause(jmse);
                throw re;
            }
        }
        }
    }

    /** Updates the factory and consumer tables held by the resource adapter
     *
     */
    private void
    updateFactoryConsumerTables(MessageEndpointFactory endpointFactory, ActivationSpec spec)
    {
        cID = ra.addEndpointConsumer(this);
        fID = ra.addMessageFactory(endpointFactory);
        ra.addFactorytoConsumerLink(fID, cID);
        //System.out.println("MQRA:EC:updateFactoryConsumerTables:fID="+fID+" cID="+cID+":"+spec.toString());
    }

    /** Sets destinationType from the ActivationSpec
     *  instance passed in and validates related configs
     */
    private void setDestinationType()
    throws ResourceException {
        String destName = aSpec.getDestination();
        String destinationLookup = aSpec.getDestinationLookup();
        Object destObj = null;
        if (destinationLookup != null) {
            try {
                destObj = Util.jndiLookup(destinationLookup);
            } catch (NamingException e) {
                String errorMessage = "MQRA:EC:Invalid destinationLookup " + destinationLookup + " configured in ActivationSpec of MDB for no JNDI name found";
                throw new ResourceException(errorMessage, e);
            }
            if (destObj != null) {
                if (destObj instanceof com.sun.messaging.Destination) {
                    destName = ((com.sun.messaging.Destination) destObj).getName();
                } else {
                    String errorMessage = "MQRA:EC:Invalid destinationLookup " + destinationLookup + " configured in ActivationSpec of MDB, The JNDI object is required to be a Destionation";
                    throw new NotSupportedException(errorMessage);
                }
            } else {
                String errorMessage = "MQRA:EC:Invalid destinationLookup " + destinationLookup + " configured in ActivationSpec of MDB for JNDI object is null";
                throw new NotSupportedException(errorMessage);
            }
        }
        try {
            if (destObj != null) {
                if (destObj instanceof com.sun.messaging.Queue) {
                    if (aSpec._isDestTypeTopicSet())
                        throw new InvalidPropertyException("MQRA:EC:Inconsistent destinationType is set for destinationLookup " + destinationLookup);
                    this.destination = new com.sun.messaging.Queue(destName);
                    this.destinationType = ClientConstants.DESTINATION_TYPE_QUEUE;
                } else {
                    if (destObj instanceof com.sun.messaging.Topic) {
                        if (aSpec._isDestTypeQueueSet())
                            throw new InvalidPropertyException("MQRA:EC:Inconsistent destinationType is set for destinationLookup " + destinationLookup);
                        this.destination = new com.sun.messaging.Topic(destName);
                        this.destinationType = ClientConstants.DESTINATION_TYPE_TOPIC;
                    }
                }
            } else if (aSpec._isDestTypeQueueSet()) {
                this.destination = new com.sun.messaging.Queue(destName);
                this.destinationType = ClientConstants.DESTINATION_TYPE_QUEUE;
            } else {
                // destinationType is TOPIC by default
                this.destination = new com.sun.messaging.Topic(destName);
                this.destinationType = ClientConstants.DESTINATION_TYPE_TOPIC;
            }
        } catch (JMSException jmse) {
        	String errorMessage;
        	if (destName==null){
        		errorMessage="MQRA:EC:No destination configured in ActivationSpec of MDB";
        	} else {
        		errorMessage="MQRA:EC:Invalid destination "+destName+" configured in ActivationSpec of MDB";
        	}
            NotSupportedException nse = new NotSupportedException(errorMessage);
            nse.initCause(jmse);
            throw nse;
        }
        //XXX:Does MDB Deployment need physical dest to exist?
        //If so need Admin API to check

        //XXX:Can MDB depoyment handle destname as resource-ref vs. physical dest name?
        //If so, need to handle in ActivationSpec (how will AS handle this?)
    }

    /** Sets isDurable from the ActivationSpec
     *  instance passed in and validates related configs
     */
    private void setIsDurable()
    throws NotSupportedException {
        //If durable subscription, validate subscriptionName
        if (aSpec._isDurableSet()) {
            if (aSpec.getSubscriptionScope() == null) {
                String sName = this.subscriptionName;
                if ((sName == null) || (sName.length() <= 0)) {
                    throw new NotSupportedException("MQRA:EC:Need Valid SubscriptionName-"+sName);
                }
            }
            //Setting this indicates everything is valid
            this.isDurable = true;
        }
    }

    /**
     *  Create a direct mode message consumer
     */
    protected void createDirectMessageConsumer()
    throws NotSupportedException {

        try {
            //Use method that avoids allocation via the ConnectionManager
            this.dc = (DirectConnection)dcf._createConnection(username, password);
            if (this.clientId != null) {
                this.dc._setClientID(this.clientId);
            }
            
            if (ResourceAdapter._isFixCR6760301()){
                this.ds = (DirectSession)this.dc.createSessionForRAEndpoint();
            } else {
                this.ds = (DirectSession)this.dc.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            }
            
            //Set the Session to be an MDB Session
            this.ds._setMDBSession(true);
            if (aSpec.getSubscriptionScope() != null) {
                subscriptionName = getSubscriptionName();
                if (this.isDurable) {
                    this.msgConsumer = this.ds.createSharedDurableConsumer(
                            (Topic)this.destination,
                            subscriptionName,
                            this.selector);
                } else {
                    this.msgConsumer = this.ds.createSharedConsumer(
                            (Topic)this.destination,
                            subscriptionName,
                            this.selector);
                }
            } else {
                if (this.isDurable) {
                    this.msgConsumer = this.ds.createDurableSubscriber(
                            (Topic)this.destination,
                            this.subscriptionName,
                            this.selector, false);
                } else {
                    this.msgConsumer = this.ds.createConsumer(
                            this.destination, this.selector);
                }
            }
            this.msgListener = new MessageListener(this, this.endpointFactory,
                    this.aSpec, this.noAckDelivery, this.useRADirect);
            this.msgConsumer.setMessageListener(this.msgListener);
            this.dc.start();
            updateFactoryConsumerTables(this.endpointFactory, this.aSpec);
        } catch (JMSException jmse) {
            if (this.dc != null) {
                try {
                    this.dc.close();
                } catch (JMSException jmsecc) {
                    //System.out.println("MQRA:EC:closed xac on conn creation exception-"+jmsecc.getMessage());
                }
                this.dc = null;
            }
            NotSupportedException nse = new NotSupportedException(
                    "MQRA:EC:Error creating Direct Message Consumer:\n"+jmse.getMessage());
            nse.initCause(jmse);
            throw nse;
        }
    }

    /**
     *  Start the Direct MessageConsumer
     */
    protected void startDirectConsumer()
    throws NotSupportedException {
        
    }
    /**
     *  Stop the Direct MessageConsumer
     */
    protected void stopDirectMessageConsumer()
    throws Exception {
        stopping = true;
        synchronized (this) {
            if (this.msgConsumer != null) {
                try {
                    if (this.msgListener != null) {
                        this.ds._stop();
                        msgListener.waitForAllOnMessageRunners();
                        msgListener.releaseOnMessageRunners();
                        //this.ds._close(); //Will be done by dc.close
                    }
                } catch (JMSException jmse) {
                    ResourceException re = 
                            new ResourceException(
                            "MQRA:EC:Error on closing Direct MessageConsumer");
                    re.initCause(jmse);
                    throw re;
                }
            }
            if (this.dc != null) {
                try {
                    this.dc.close();
                } catch (JMSException jmse) {
                    ResourceException re =
                            new ResourceException(
                            "MQRA:EC:Error closing DircetConnection");
                    re.initCause(jmse);
                    throw re;
                }
            }
        }
    }

    /**
     * Get or generate the subscription name
     */
    protected String getSubscriptionName() {
        if (aSpec.getSubscriptionScope() == null)
            return aSpec.getSubscriptionName();

        String subscriptionName = null;
        if (aSpec.getSubscriptionName() == null) {
            // user doesn't specify activationSpec property subscriptionName,
            // so we generate a name according to chapter 12.1 of jms20 spec
            String activationName = this.endpointFactory.getActivationName();
            if (aSpec.getSubscriptionScope().equals("Cluster")) {
                // Cluster scope
                subscriptionName = activationName;
            } else {
                // Instance scope
                if (aSpec._isInClusteredContainerSet()) {
                    // it is in a glassfish cluster
//                    String instanceName = this.ra.getBootstrapContext().getInstanceName();
//                    subscriptionName = instanceName + "_" + activationName;
                } else {
                    // it is standalone glassfish instance
                    subscriptionName = activationName;
                }
            }
        } else {
            // user specifies activationSpec property subscriptionName
            if (aSpec.getSubscriptionScope().equals("Instance")) {
                // Instance scope
                if (aSpec._isInClusteredContainerSet()) {
                    // it is in a glassfish cluster
//                    String instanceName = this.ra.getBootstrapContext().getInstanceName();
//                    subscriptionName = instanceName + "_" + aSpec.getSubscriptionName();
                } else {
                    // it is standalone glassfish instance
                    subscriptionName = aSpec.getSubscriptionName();
                }
            } else {
                // Cluster scope
                subscriptionName = aSpec.getSubscriptionName();
            }
        }
        _loggerIM.fine("MQRA:EC:Use subscription name '" + subscriptionName + "' for endpoint activation");
        return subscriptionName;
    }

    protected void checkSubscriptionScopeAndClientId() throws NotSupportedException {
        if (aSpec.getSubscriptionScope() != null) {
            if (QUEUE.equals(aSpec.getDestinationType())) {
                NotSupportedException nse = new NotSupportedException("MQRA:EC:Error:Bad parameter");
                nse.initCause(new InvalidPropertyException(_lgrMID_EXC + "subscriptionScope must not be set if destinationType is " + QUEUE));
                throw nse;
            } else if (TOPIC.equals(aSpec.getDestinationType()) && clientId != null) {
                NotSupportedException nse = new NotSupportedException("MQRA:EC:Error:Bad parameter");
                nse.initCause(new InvalidPropertyException(_lgrMID_EXC + "clientId must not be set if subscriptionScope is set"));
                throw nse;
            }
        }
    }
}
