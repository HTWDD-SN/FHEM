/*******************************************************************************
 * Copyright (c) 2012, Institute for Pervasive Computing, ETH Zurich.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * 
 * This file is part of the Californium (Cf) CoAP framework.
 ******************************************************************************/
package de.htwdd.sn.coapclient;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.logging.Level;

import ch.ethz.inf.vs.californium.coap.*;
import ch.ethz.inf.vs.californium.coap.registries.MediaTypeRegistry;
import ch.ethz.inf.vs.californium.endpoint.resources.RemoteResource;
import ch.ethz.inf.vs.californium.endpoint.resources.Resource;
import ch.ethz.inf.vs.californium.util.Log;
import ch.ethz.inf.vs.californium.coap.registries.CodeRegistry;

/**
 * This class implements a simple CoAP client for testing purposes. Usage:
 * <p>
 * {@code java -jar SampleClient.jar [-l] METHOD URI [PAYLOAD]}
 * <ul>
 * <li>METHOD: {GET, POST, PUT, DELETE, DISCOVER, OBSERVE}
 * <li>URI: The URI to the remote endpoint or resource}
 * <li>PAYLOAD: The data to send with the request}
 * </ul>
 * Options:
 * <ul>
 * <li>-l: Loop for multiple responses}
 * </ul>
 * Examples:
 * <ul>
 * <li>{@code SampleClient DISCOVER coap://localhost}
 * <li>{@code SampleClient POST coap://someServer.org:5683 my data}
 * </ul>
 * 
 * @author Dominique Im Obersteg, Daniel Pauli, and Matthias Kovatsch
 */
public class CoapClient implements ResponseHandler {

	// Our collection of classes that are subscribed as listeners of our
	protected  CoapResponseListener listener;

	// resource URI path used for discovery
	private static final String DISCOVERY_RESOURCE = "/.well-known/core";

	private static final int IDX_METHOD = 0;
	private static final int IDX_URI = 1;
	private static final int IDX_PAYLOAD_OPTIONS = 2;

	public static final String GET = "GET";
	public static final String PUT = "PUT";
	public static final String DISCOVER = "DISCOVER";
	public static final String OBSERVE = "OBSERVE";
	public static final String POST = "POST";
	public static final String DELETE = "DELETE";
	public static final String ERROR = "ERROR";

	private static Map<String, byte[]> observeTokens = new HashMap<String, byte[]>();
	
	// Method for listener classes to register themselves
	public CoapClient(CoapResponseListener listener) {
		this.listener = listener;
	}

	/*
	 * process request
	 */
	public void process(String method, URI uri, List<Option> options, String payload, boolean waitForMultipleResponses) {
		Response response = null;
		String error = "";

		Log.setLevel(Level.ALL);
		Log.init();

		// check if mandatory parameters specified
		if (method == null) {
			error = "Method not specified";
			listener.notify(uri.toString(), ERROR, error, null);
			System.err.println(error);
			return;
		}

		if (uri == null) {
			error = "URI not specified";
			System.err.println(error);
			listener.notify("", ERROR, error, null);
			return;
		}

		// create request according to specified method
		Request request = newRequest(method);
		if (request == null) {
			error = "Unknown method: " + method;
			System.err.println(error);
			listener.notify(uri.toString(), ERROR, error, null);
			return;
		}

		// set request URI
		if (method.equals(DISCOVER)
				&& (uri.getPath() == null || uri.getPath().isEmpty() || uri
						.getPath().equals("/"))) {
			// add discovery resource path to URI
			try {
				uri = new URI(uri.getScheme(), uri.getAuthority(),
						DISCOVERY_RESOURCE, uri.getQuery());

			} catch (URISyntaxException e) {
				
				error = "Failed to parse URI: " + e.getMessage();
				System.err.println(error);
				listener.notify(uri.toString(), ERROR, error, null);
				return;
			}
		}

		request.setURI(uri);
		request.setPayload(payload);
		request.setToken(TokenManager.getInstance().acquireToken());
		request.setContentType(MediaTypeRegistry.TEXT_PLAIN);

		boolean isDeObserve = false;
		boolean isExecutable = true;
		for(Option option : options)
			if(option.getOptionNumber() == 6){
				if(option.getIntValue() == 0) {
					request.setObserve();
					request.registerResponseHandler(this);
					if(!observeTokens.containsKey(uri.toASCIIString()))
						observeTokens.put(uri.toASCIIString(), request.getToken());
					else
						isExecutable = false;
				} else if(option.getIntValue() == 1) {
					if(observeTokens.containsKey(uri.toASCIIString())) {
						request.setToken(observeTokens.get(uri.toASCIIString()));
						observeTokens.remove(uri.toASCIIString());
						isDeObserve = true;
					} else
						isExecutable = false;
				}
			}

		if(isExecutable) {
			request.setOptions(options);

			// enable response queue in order to use blocking I/O
			if (!isDeObserve)
				request.enableResponseQueue(true);
			request.prettyPrint();

			// execute request
			try {
				request.execute();
				if (!isDeObserve) {
					// loop for receiving multiple responses
					do {

						// receive response
						System.out.println("Receiving response...");

						try {
							response = request.receiveResponse();
						} catch (InterruptedException e) {
							error = "Failed to receive response: " + e.getMessage();
							System.err.println(error);
							listener.notify(uri.toString(), ERROR, error, null);
							return;
						}

						// output response
						if (response != null) {

							response.prettyPrint();
							System.out.println("Time elapsed (ms): "
									+ response.getRTT());

							if (response.getCode() == CodeRegistry.RESP_FORBIDDEN
									|| response.getCode() == CodeRegistry.RESP_METHOD_NOT_ALLOWED) {
								listener.notify(
										request.getUriHost() + request.getUriPath()
										, ERROR
										, "METHOD " + method + " is not allowed", null);
							} else if (response.getCode() == CodeRegistry.RESP_CHANGED) {
								listener.notify(
										request.getUriHost() + request.getUriPath()
										, method
										, "changed", null);
							} else {
								listener.notify(
										request.getUriHost() + request.getUriPath(),
										method,
										response.getPayloadString(),
										convertOptionList(response.getOptions()));
							}

							// check of response contains resources
							if (response.getContentType() == MediaTypeRegistry.APPLICATION_LINK_FORMAT) {

								String linkFormat = response.getPayloadString();

								// create resource three from link format
								Resource root = RemoteResource.newRoot(linkFormat);
								if (root != null) {

									// output discovered resources
									System.out.println("\nDiscovered resources:");
									root.prettyPrint();

								} else {
									error = "Failed to parse link format";
									System.err.println(error);
									listener.notify(request.getUriHost() + request.getUriPath(), ERROR, error, null);
								}
							} else {

								// check if link format was expected by client
								if (method.equals(DISCOVER)) {
									error = "Server error: Link format not specified";
									System.err.println(error);
									listener.notify(request.getUriHost() + request.getUriPath(), ERROR, error, null);
								}
							}

						} else {

							error = "Request timed out";
							listener.notify(request.getUriHost() + request.getUriPath(), ERROR, error, null);

							// no response received
							System.err.println(error);
							break;
						}

					} while (waitForMultipleResponses);
				}

			} catch (UnknownHostException e) {
				error = "Unknown host: " + e.getMessage();
				System.err.println(error);
				listener.notify(request.getUriHost() + request.getUriPath(), ERROR, error, null);

			} catch (IOException e) {
				error = "Failed to execute request: " + e.getMessage();
				System.err.println(error);
				listener.notify(request.getUriHost() + request.getUriPath(), ERROR, error, null);
			}
		}
	}

	/*
	 * Instantiates a new request based on a string describing a method.
	 * 
	 * @return A new request object, or null if method not recognized
	 */
	private static Request newRequest(String method) {
		if (method.equals(GET)) {
			return new GETRequest();
		} else if (method.equals(POST)) {
			return new POSTRequest();
		} else if (method.equals(PUT)) {
			return new PUTRequest();
		} else if (method.equals(DELETE)) {
			return new DELETERequest();
		} else if (method.equals(DISCOVER)) {
			return new GETRequest();
		} else if (method.equals(OBSERVE)) {
			return new GETRequest();
		} else {
			return null;
		}
	}

	//handle Observe responses
	@Override
	public void handleResponse(Response response) {
		Request request = response.getRequest();
		listener.notify(
				request.getUriHost() + request.getUriPath(), 
				OBSERVE, 
				response.getPayloadString(),
				convertOptionList(response.getOptions()));
	}

	private HashMap<String, String> convertOptionList(List<Option> options) {
		HashMap<String, String> hashOptions = new HashMap<>();
		Iterator iterator = options.iterator();
		while(iterator.hasNext()) {
			Option option = (Option)iterator.next();
			hashOptions.put(option.getName(), option.toString());
		}
		return hashOptions;
	}
}