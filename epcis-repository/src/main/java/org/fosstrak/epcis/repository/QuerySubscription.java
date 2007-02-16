/*
 * Copyright (c) 2006 ETH Zurich, Switzerland. All rights reserved. For copying
 * and distribution information, please see the file LICENSE.
 */

package org.accada.epcis.repository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.GregorianCalendar;

import javax.xml.namespace.QName;
import javax.xml.rpc.ServiceException;

import org.accada.epcis.soapapi.AggregationEventType;
import org.accada.epcis.soapapi.EPCISQueryBodyType;
import org.accada.epcis.soapapi.EPCISQueryDocumentType;
import org.accada.epcis.soapapi.EPCISServiceBindingStub;
import org.accada.epcis.soapapi.EPCglobalEPCISServiceLocator;
import org.accada.epcis.soapapi.EventListType;
import org.accada.epcis.soapapi.ImplementationException;
import org.accada.epcis.soapapi.ObjectEventType;
import org.accada.epcis.soapapi.Poll;
import org.accada.epcis.soapapi.QuantityEventType;
import org.accada.epcis.soapapi.QueryParam;
import org.accada.epcis.soapapi.QueryResults;
import org.accada.epcis.soapapi.TransactionEventType;
import org.apache.axis.MessageContext;
import org.apache.axis.encoding.SerializationContext;
import org.apache.axis.message.NullAttributes;
import org.apache.axis.types.URI;
import org.apache.log4j.Logger;

/**
 * Implements a subscription to a query. Created upon using subscribe() on the
 * querying interface side.
 * 
 * @author Alain Remund, Arthur van Dorp
 */
public class QuerySubscription implements Serializable {

    /**
     * Generated ID for serialization. Adapt if you change this class in a
     * backwards incompatible way.
     */
    private static final long serialVersionUID = -401176555052383495L;

    private static final Logger LOG = Logger.getLogger(QuerySubscription.class);

    /**
     * SubscriptionID.
     */
    protected String subscriptionID;

    /**
     * Query parameters.
     */
    private QueryParam[] queryParams;

    /**
     * Destination URI to send results to.
     */
    private URI dest;

    /**
     * Initial record time.
     */
    private GregorianCalendar initialRecordTime;

    /**
     * Whether to send results if nothing new available.
     */
    private Boolean reportIfEmpty;

    /**
     * queryName.
     */
    private String queryName;

    protected EPCglobalEPCISServiceLocator service = null;

    /**
     * Constructor to be used when recreating from storage.
     * 
     * @param subscriptionID
     *            subscriptionID.
     * @param queryParams
     *            Query parameters.
     * @param dest
     *            Destination URI.
     * @param reportIfEmpty
     *            Whether to report when nothing changed.
     * @param initialRecordTime
     *            Time from when on events should be reported on first
     *            execution.
     * @param lastTimeExecuted
     *            Last time the query got executed.
     * @param queryName
     *            queryName.
     */
    public QuerySubscription(final String subscriptionID,
            final QueryParam[] queryParams, final URI dest,
            final Boolean reportIfEmpty,
            final GregorianCalendar initialRecordTime,
            final GregorianCalendar lastTimeExecuted, final String queryName) {
        this.subscriptionID = subscriptionID;
        this.dest = dest;
        this.initialRecordTime = initialRecordTime;
        this.reportIfEmpty = reportIfEmpty;
        this.queryName = queryName;

        // add time restriction to query params
        QueryParam restrictTime = new QueryParam();
        restrictTime.setName("GE_recordTime");
        restrictTime.setValue(lastTimeExecuted);

        this.queryParams = new QueryParam[queryParams.length + 1];
        System.arraycopy(queryParams, 0, this.queryParams, 0,
                queryParams.length);
        this.queryParams[queryParams.length] = restrictTime;

        // initialize the service locator through which the queries will be sent
        MessageContext msgContext = MessageContext.getCurrentContext();
        String queryUrl = (String) msgContext.getProperty(MessageContext.TRANS_URL);
        service = new EPCglobalEPCISServiceLocator();
        service.setEPCglobalEPCISServicePortEndpointAddress(queryUrl);
    }

    /**
     * Runs the query assigned to this subscription. Advances lastTimeExecuted.
     * 
     * @throws ImplementationException
     *             For various reasons.
     */
    public void executeQuery() {
        Poll poll = new Poll(queryName, queryParams);
        try {

            // run the query
            LOG.debug("Running the subscribed query with ID '" + subscriptionID
                    + "'.");
            QueryResults result = null;
            try {
                EPCISServiceBindingStub epcisQueryService = (EPCISServiceBindingStub) service.getEPCglobalEPCISServicePort();
                result = epcisQueryService.poll(poll);
            } catch (RemoteException e) {
                // FIXME this exception should be sent back to the client!!!
                String msg = e.getMessage();
                LOG.error(msg, e);
                throw e; // re-throw exception for now ...
            }
            result.setSubscriptionID(subscriptionID);
            EventListType eventList = result.getResultsBody().getEventList();

            // check if we have an empty result list
            boolean isEmpty = false;
            if (eventList == null) {
                isEmpty = true;
            } else {
                AggregationEventType[] aggrEvents = eventList.getAggregationEvent();
                ObjectEventType[] objEvents = eventList.getObjectEvent();
                QuantityEventType[] quantEvents = eventList.getQuantityEvent();
                TransactionEventType[] transEvents = eventList.getTransactionEvent();
                if (aggrEvents == null && objEvents == null
                        && quantEvents == null && transEvents == null) {
                    isEmpty = true;
                } else {
                    int nofAggrEvents = (aggrEvents != null)
                            ? aggrEvents.length
                            : 0;
                    int nofObjEvents = (objEvents != null)
                            ? objEvents.length
                            : 0;
                    int nofQuantEvents = (quantEvents != null)
                            ? quantEvents.length
                            : 0;
                    int nofTransEvents = (transEvents != null)
                            ? transEvents.length
                            : 0;
                    if (nofAggrEvents == 0 && nofObjEvents == 0
                            && nofQuantEvents == 0 && nofTransEvents == 0) {
                        isEmpty = true;
                    }
                    LOG.debug("Subscribed query with ID '" + subscriptionID
                            + "' contains " + nofAggrEvents
                            + " AggregationEvents, " + nofObjEvents
                            + " ObjectEvents, " + nofQuantEvents
                            + " QuantityEvents, " + nofTransEvents
                            + " TransactionEvents.");
                }
            }

            if (!reportIfEmpty && isEmpty) {
                LOG.debug("Query returned no results, nothing to report.");
                return;
            }

            EPCISQueryBodyType queryBody = new EPCISQueryBodyType();
            queryBody.setQueryResults(result);

            EPCISQueryDocumentType queryDoc = new EPCISQueryDocumentType();
            queryDoc.setCreationDate(new GregorianCalendar());
            queryDoc.setEPCISBody(queryBody);

            // serialize the response
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(baos);
            SerializationContext serContext = new SerializationContext(writer);
            QName queryDocXMLType = EPCISQueryDocumentType.getTypeDesc().getXmlType();
            serContext.setWriteXMLType(queryDocXMLType);
            serContext.serialize(queryDocXMLType, new NullAttributes(),
                    queryDoc, queryDocXMLType, EPCISQueryDocumentType.class,
                    false, true);
            writer.flush();
            String data = baos.toString();

            // set up connection and send data to given destination
            URL serviceUrl = new URL(dest.toString());
            LOG.debug("Sending results of subscribed query with ID '"
                    + subscriptionID + "' to '" + serviceUrl + "'.");
            int responseCode = sendData(serviceUrl, data);
            LOG.debug("Response " + responseCode);

        } catch (IOException e) {
            String msg = "An error opening a connection to '" + dest
                    + "' or serializing and sending contents occured: "
                    + e.getMessage();
            LOG.error(msg, e);
        } catch (ServiceException e) {
            String msg = "An error retrieving the EPCIS query service occured: "
                    + e.getMessage();
            LOG.error(msg, e);
        }
    }

    /**
     * Sends the given data String to the specified URL.
     * 
     * @param url
     *            The URL to send the data to.
     * @param data
     *            The data to send.
     * @return The HTTP response code.
     * @throws IOException
     *             If a communication error occured.
     */
    private int sendData(URL url, String data) throws IOException {
        data.concat("\n");
        // setup connection
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        // connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-type", "text/plain");
        connection.setRequestProperty("Content-length", "" + data.length());
        // connection.setDoInput(true);
        connection.setDoOutput(true);

        // encode data
        // CharBuffer buf = CharBuffer.wrap(data);
        // Charset charset = Charset.forName("UTF-8");
        // CharsetEncoder encoder = charset.newEncoder();

        // send data
        OutputStreamWriter out = new OutputStreamWriter(
                connection.getOutputStream());
        LOG.debug("Sending data: " + data);
        out.write(data);
        out.flush();

        // get response
        // connection.getInputStream();
        int responseCode = connection.getResponseCode();

        // disconnect
        connection.disconnect();

        return responseCode;
    }

    /**
     * @return The initial record time.
     */
    public GregorianCalendar getInitialRecordTime() {
        return initialRecordTime;
    }

}