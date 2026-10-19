package com.fiskerwarm.internet;
import org.json.*;
import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
/** HTTPS only, standard system trust/hostname validation, no redirects or insecure fallback. */
public final class RelayClient {
 private final Config c;
 private volatile HttpsURLConnection active;
 public void cancel(){HttpsURLConnection x=active;if(x!=null)x.disconnect();}
 public RelayClient(Config config){c=config;}
 public static final class Failure extends Exception {
  public final String code;public final int status;
  Failure(String c,int s){super(c);code=c;status=s;}
 }
 public JSONObject call(JSONObject body)throws Exception{
  body.put("pair",c.pair);byte[] bytes=body.toString().getBytes("UTF-8");
  HttpsURLConnection x=(HttpsURLConnection)new URL(c.relay+(c.relay.endsWith("/")?"api/relay":"/api/relay")).openConnection();
  active=x;
  x.setInstanceFollowRedirects(false);x.setConnectTimeout(12000);x.setReadTimeout(30000);x.setRequestMethod("POST");x.setDoOutput(true);
  x.setRequestProperty("Content-Type","application/json");x.setRequestProperty("Accept","application/json");
  x.setRequestProperty("Authorization","Bearer "+c.token);x.setRequestProperty("OAI-Sites-Authorization","Bearer "+c.siteToken);
  x.setFixedLengthStreamingMode(bytes.length);
  try{
   try(OutputStream o=x.getOutputStream()){o.write(bytes);}
   int code=x.getResponseCode();
   if(code<200||code>=300){
    // Codes are allowlisted; server/transport text can never leak secrets into diagnostics.
    String reason="HTTP_"+code;InputStream e=x.getErrorStream();
    if(e!=null)try{String raw=read(e);String r=new JSONObject(raw).optString("error");if(r.matches("[A-Z_]{1,60}"))reason=r;}catch(Exception ignored){}
    throw new Failure(reason,code);
   }
   try(InputStream i=x.getInputStream()){return new JSONObject(read(i));}
  }finally{x.disconnect();if(active==x)active=null;}
 }
 private static String read(InputStream i)throws IOException{
  ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[2048];int n;while((n=i.read(buf))!=-1){if(b.size()+n>65536)throw new IOException("RESPONSE_LIMIT");b.write(buf,0,n);}return b.toString("UTF-8");
 }
 public static String error(Exception e){return e instanceof Failure?((Failure)e).code:"NETWORK_OR_LOCAL_ERROR";}
}
