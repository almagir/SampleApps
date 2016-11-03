package com.nymi.api.wrapper;

import org.json.JSONObject;

import com.nymi.api.wrapper.NymiJavaApi.FoundStatus;
import com.nymi.api.wrapper.NymiJavaApi.HapticNotification;
import com.nymi.api.wrapper.NymiJavaApi.KeyType;
import com.nymi.api.wrapper.NymiJavaApi.PresenceStatus;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Listener implements Runnable {

	private AtomicBoolean quit = new AtomicBoolean(false);
	private NapiCallbacks callbacks = null;
	private Thread listenThread = null;

	public Listener (NapiCallbacks cb)
	{
		callbacks = cb;
	}
	
	public void start() {
		if (listenThread == null) {
			listenThread = new Thread (this, "listener");
			listenThread.start();
		}
	}
	
    public void run() {
        
    	System.out.println("Starting new listener thread");
    	
        while (!quit.get()) {
            NativeLibWrapper.NapiReturnStruct.ByValue result = NativeLibWrapper.INSTANCE.jsonNapiGetSD(quit.get(), 100);
            quit.set(result.getQuit());
            
            if (result.getOutcome() == 0) {    // 0 == LibNapi.JsonGetOutcome.okay
                //System.out.println("received message: " + result.getMessage());
                
                try {
                	JSONObject jobj = new JSONObject(result.getMessage());
                     
	                //handle any passed errors
                	if (jobj.has("errors")) {
	                    handleNapiError(jobj);
	                    continue;
	                }
                	if (jobj.has("successful"))
                			if (!jobj.getBoolean("successful")) {
        	                    handleNapiError(jobj);
        	                    continue;
        	                }

	                //delegate to proper op handler
	                if (jobj.has("operation")) {
	                    String operation = jobj.getJSONArray("operation").getString(0);
						switch (operation) {
							case "provision":
								handleOpProvision(jobj);
								break;
							case "info":
								handleOpInfo(jobj);
								break;
							case "random":
								handleOpRandom(jobj);
								break;
							case "symmetricKey":
								handleOpSymmetric(jobj);
								break;
							case "sign":
								handleOpSignature(jobj);
								break;
							case "totp":
								handleOpTotp(jobj);
								break;
							case "buzz":
								handleOpNotified(jobj);
								break;
							case "notifications":
								handleOpApiNotifications(jobj);
								break;
							case "revoke":
								handleOpRevokeProvision(jobj);
								break;
							case "key":
								handleOpKey(jobj);
								break;
							default:
								logMessage(jobj);
								break;
						}
	                }
                } catch (JSONException e){
                	System.out.println("Caught exception in JSON operation handler: " + e.getMessage());
                	e.printStackTrace();
                }
            }
        }
    }

    void logMessage(JSONObject jobj) {
    	System.out.println("Unknown message received from API: " + jobj.toString());
    }
    
    void handleNapiError(JSONObject jobj) {

    	NapiError nErr = new NapiError();
        //error message specifies the operation
        nErr.errorString += " Operation: " + jobj.getString("path");
        
        //extract the array of errors
        if (jobj.has("errors")) {
            JSONArray errors = jobj.getJSONArray("errors");
            nErr.errorString += ", Error message(s):";
            if (errors.length() > 0)
	            for(int i=0; i < errors.length(); i++){
	            	String errType = errors.getJSONArray(i).getString(1);
	                String errMsg = errors.getJSONArray(i).getString(0);
	                nErr.errorString += "{" + errType + ":" + "'" +errMsg + "'} ";
	            }
        }
        callbacks.onError(nErr);
    }
        
        //some utility functions
        //----------------------
        String getExchange(JSONObject jobj, boolean errorIfNoExchange){
            
            if (!jobj.has("exchange")) {
                if (errorIfNoExchange) {
                    String errMsg = "Could not find JSON field \"exchange\" in the JSON obj:\n";
                    errMsg += jobj.toString();
                    NapiError err = new NapiError();
                    err.errorString = errMsg;
                    callbacks.onError(err);
                }
                return "";
            } else
            	return jobj.getString("exchange");
            
        }
        
        String getPid(JSONObject jobj){
            
        	if (jobj.has("request")) {
        		jobj.getJSONObject("request");
        		if (jobj.getJSONObject("request").has("pid"))
        				return jobj.getJSONObject("request").getString("pid");
            }
            return "";
        }
        
        NapiError genMissingJsonKeyErr(String key, JSONObject jobj){
            
            String errMsg = "Could not find JSON field \"" + key + "\" in the JSON obj:\n";
            errMsg += jobj.toString();
            NapiError nErr = new NapiError();
            nErr.errorString = errMsg;
            return nErr;
        }
        
        // Operation Handlers
        private void handleOpProvision(JSONObject jobj) {
            JSONArray ops = jobj.getJSONArray("operation");
            if (ops.getString(1).equals("report")){
                if (ops.getString(2).equals("patterns")){
                    //handle receipt of provisioning pattern
                	if (jobj.has("event")) {
                		JSONArray jsonpatterns = jobj.getJSONObject("event").getJSONArray("patterns");
                        List<String> patterns = new ArrayList<>();
                        for (int i = 0; i < jsonpatterns.length(); ++i) {
                            patterns.add(jsonpatterns.getString(i));
                        }
                        callbacks.onAgreement(patterns);
                    }
                }
                else if (ops.getString(2).equals("provisioned")){
                    //handle provisioned device
                    if (jobj.has("event"))
                    	if (jobj.getJSONObject("event").has("kind"))
                    		if (jobj.getJSONObject("event").has("info"))
                    				if (jobj.getJSONObject("event").getJSONObject("info").has("pid")) {
			                            String pid = jobj.getJSONObject("event").getJSONObject("info").getString("pid");
			                            callbacks.onProvision(new NymiProvision(pid));
                    				}
                }
            }
            else if (ops.getString(1).equals("run") && (ops.getString(2).equals("start") || ops.getString(2).equals("stop"))){
                String provState = ops.getString(2);
                callbacks.onProvisionModeChange(provState);
            }
        }

        void handleOpInfo(JSONObject jobj) {
            
            String exchange = getExchange(jobj,true);
            if (exchange.isEmpty()) return;
            
			List<NymiProvision> provList = new ArrayList<>();
            if (exchange.equals("provisions") || exchange.equals("provisionsPresent")) {
                if (jobj.has("response"))
                	if (jobj.getJSONObject("response").has(exchange)){
    				JSONArray napiProvList = jobj.getJSONObject("response").getJSONArray(exchange);
    				for (int i = 0 ; i < napiProvList.length() ; i++ ) {
    					provList.add(new NymiProvision(napiProvList.getString(i)));
    				}
    			}
                callbacks.getProvisionList(provList);
            }
            else if (exchange.contains("deviceinfo")) {
                int pidstart = exchange.indexOf("deviceinfo") + "deviceinfo".length();
                String pid = exchange.substring(pidstart);
                if (jobj.has("response")) if (jobj.getJSONObject("response").has("provisionMap")) {
                			JSONObject pmap = jobj.getJSONObject("response").getJSONObject("provisionMap");
		                    int idx = pmap.getInt(pid);
		                    if (jobj.has("response")) if (jobj.getJSONObject("response").has("nymiband")){
		                        JSONArray nymiBands = jobj.getJSONObject("response").getJSONArray("nymiband");
		                        if (idx < nymiBands.length()){
		                            JSONObject deviceInfo = nymiBands.getJSONObject(idx);
		                            TransientNymiBandInfo ndinfo = new TransientNymiBandInfo(deviceInfo);
	                                callbacks.onDeviceInfo(true,pid,ndinfo,new NapiError());
		                        }
		                    }
                		}
            	}
        }        
        
        void handleOpRandom(JSONObject jobj){

            String pid = getPid(jobj);
            if (pid.isEmpty()) {
                callbacks.onRandom(false,pid,"",new NapiError());
                return;
            }
            
            if (!jobj.getJSONObject("response").has("pseudoRandomNumber")) {
                callbacks.onRandom(false,pid,"",genMissingJsonKeyErr("pseudoRandomNumber",jobj));
                return;
            }
            String rand = jobj.getJSONObject("response").getString("pseudoRandomNumber");
        
            callbacks.onRandom(true,pid,rand,new NapiError());
        }
        
        void handleOpSymmetric(JSONObject jobj) {

            String pid = getPid(jobj);
            if (pid.isEmpty()) {
                callbacks.onKeyCreation(false,pid,KeyType.SYMMETRIC,new NapiError());
                return;
            }
        
            if (!jobj.has("operation")) return;
            JSONArray ops = jobj.getJSONArray("operation");
            if (ops.getString(1).equals("run")){
                KeyType keyType = KeyType.SYMMETRIC;
                callbacks.onKeyCreation(jobj.getBoolean("successful"),pid,keyType,new NapiError());
            }
            else if (ops.getString(1).equals("get")){
            	if (jobj.has("response"))
            		if (jobj.getJSONObject("response").has("key")){
            			callbacks.onSymmetricKey(true,pid,jobj.getJSONObject("response").getString("key"),new NapiError());
                }
            }
        }
        
        void handleOpSignature(JSONObject jobj) {

            String pid = getPid(jobj);
            if (pid.isEmpty()) {
                callbacks.onEcdsaSign(false,pid,"","",new NapiError());
                return;
            }
            
            if (!jobj.has("response")) return;
            
            if (!jobj.getJSONObject("response").has("signature")) {
            	callbacks.onEcdsaSign(false,pid,"","",genMissingJsonKeyErr("signature",jobj));
                return;
            }
            String sig = jobj.getJSONObject("response").getString("signature");
            
            if (!jobj.getJSONObject("response").has("verificationKey")) {
            	callbacks.onEcdsaSign(false,pid,"","",genMissingJsonKeyErr("verificationKey",jobj));
                return;
            }
            String vk = jobj.getJSONObject("response").getString("verificationKey");
            
            callbacks.onEcdsaSign(true,pid,sig,vk,new NapiError());
        }
       
        void handleOpTotp(JSONObject jobj) {
            
            String pid = getPid(jobj);
            if (pid.isEmpty()) {
                callbacks.onTotpGet(false,pid,"",new NapiError());
                return;
            }
            
            if (!jobj.has("successful") || !(jobj.getBoolean("successful"))) {
                String errMsg = "Could not complete CreateTOTP request. JSON response follows:\n";
                errMsg += jobj.toString();
                NapiError nErr = new NapiError(errMsg);
                callbacks.onTotpGet(false,pid,"",nErr);
                return;
            }
            
            if (!jobj.has("operation")) return;
            JSONArray ops = jobj.getJSONArray("operation");
            if (ops.getString(1).equals("run")){
                callbacks.onTotpGet(jobj.getBoolean("successful"),pid,"",new NapiError());
            }
            else if (ops.getString(1).equals("get")){
                if (!jobj.getJSONObject("response").has("totp")) {
                	callbacks.onTotpGet(false,pid,"",genMissingJsonKeyErr("response/totp",jobj));
                    return;
                }
                String totpid = jobj.getJSONObject("response").getString("totp");
                callbacks.onTotpGet(true,pid,totpid,new NapiError());
            }
        }

        void handleOpNotified(JSONObject jobj) {
            
            String pid = getPid(jobj);
            if (pid.isEmpty()) {
                callbacks.onNotification(false,pid,HapticNotification.ERROR,new NapiError());
                return;
            }

            if (!jobj.has("request")) return;
            if (!jobj.getJSONObject("request").has("buzz")) {
            	callbacks.onNotification(false,pid,HapticNotification.ERROR,genMissingJsonKeyErr("request/buzz",jobj));
                return;
            }
            
            boolean notifyVal= jobj.getJSONObject("request").getBoolean("buzz");
            HapticNotification notifyType = (notifyVal) ? HapticNotification.NOTIFY_POSITIVE : HapticNotification.NOTIFY_NEGATIVE;
            callbacks.onNotification(true,pid,notifyType,new NapiError());
        }
        
        void handleOpApiNotifications(JSONObject jobj) {
            
        	if (!jobj.has("operation")) return;
        	JSONArray ops = jobj.getJSONArray("operation");
            
            if (ops.getString(1).equals("set")){/*response of set is received here, not handling it for now*/}
            else if (ops.getString(1).equals("report")) {
                if (jobj.has("event"))
                	if (jobj.getJSONObject("event").has("kind")){
                    
                    String eventType = jobj.getJSONObject("event").getString("kind");

                    if (eventType.equals("found-change") || eventType.equals ("presence-change")){
                        
                        String before = "", after = "", pid = "";
                        
                        if(jobj.getJSONObject("event").has("before")) before = jobj.getJSONObject("event").getString("before");
                        if(jobj.getJSONObject("event").has("after")) after = jobj.getJSONObject("event").getString("after");
                        if(jobj.getJSONObject("event").has("pid")) pid = jobj.getJSONObject("event").getString("pid");
                        
                        if (eventType.equals ("found-change")){
                            callbacks.onNymiBandFoundStatusChange(pid,FoundStatus.valueOf(before.toUpperCase()),FoundStatus.valueOf(after.toUpperCase()));
                        }
                        else if (eventType.equals ("presence-change")){
                            boolean authenticated = false;
                            if (jobj.getJSONObject("event").has("authenticated"))authenticated = jobj.getJSONObject("event").getBoolean("authenticated");
                            callbacks.onNymiBandPresenceChange(pid,PresenceStatus.valueOf("DEVICE_PRESENCE_" + before.toUpperCase()),PresenceStatus.valueOf("DEVICE_PRESENCE_" + after.toUpperCase()),authenticated);
                        }
                    }
                }
            }
            else if (ops.getString(1).equals("get") && jobj.has("response")) {
            	JSONObject response = jobj.getJSONObject("response");
                HashMap<String,Boolean> notificationsState = new HashMap<>();
                Iterator<?> keyset = response.keys();
                while (keyset.hasNext()) {
                    String key =  (String) keyset.next();
                    boolean value = response.getBoolean(key);
                    notificationsState.put(key, value);
                }
                callbacks.onNotificationsGetState(notificationsState);
            }
        }
        
        void handleOpRevokeProvision(JSONObject jobj) {

            String pid = getPid(jobj);
            if (pid.isEmpty()) {
                callbacks.onProvisionRevoked(false,pid,new NapiError());
                return;
            }

            callbacks.onProvisionRevoked(true,pid,new NapiError());
        }

        void handleOpKey(JSONObject jobj){

            if (!jobj.has("operation")) return;
        	boolean deletion;
        	JSONArray ops = jobj.getJSONArray("operation");
        	if (ops.length() < 2) return;
        	deletion = ops.getString(1).equals("delete");

            String pid = getPid(jobj);
            if (pid.isEmpty()) {
            	if (deletion)
            		callbacks.onKeyRevocation(false,pid,KeyType.ERROR,new NapiError());
            	else
            		callbacks.onKeyCreation(false,pid,KeyType.ERROR,new NapiError());
                return;
            }

            KeyType keyType = KeyType.ERROR;
            if (!jobj.has("request")) {
                callbacks.onKeyCreation(false,pid,KeyType.ERROR,new NapiError());
                return;
            }
            if (!jobj.has("response")) {
                callbacks.onKeyCreation(false,pid,KeyType.ERROR,new NapiError());
                return;
            }
            if (jobj.getJSONObject("request").getBoolean("symmetric") && !jobj.getJSONObject("response").getBoolean("symmetric")){
                keyType = KeyType.SYMMETRIC;
            }
            else if (jobj.getJSONObject("request").getBoolean("totp") && !jobj.getJSONObject("response").getBoolean("totp")){
                keyType = KeyType.TOTP;
            }
            if (deletion)
            	callbacks.onKeyRevocation(true,pid,keyType,new NapiError());
            else
            	callbacks.onKeyCreation(true,pid,keyType,new NapiError());
        }        
        
}
