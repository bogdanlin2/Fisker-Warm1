package com.fiskerwarm.internet;
import org.json.*;
public final class JsonWire {
 private JsonWire(){}
 public static JSONObject command(Wire.Command c)throws JSONException{return new JSONObject().put("v",1).put("pair",c.pair).put("id",c.id).put("issued",c.issued).put("expires",c.expires).put("type",c.type).put("mode",c.mode).put("mac",c.mac);}
 public static Wire.Command command(JSONObject j)throws JSONException{
  if(j.getInt("v")!=1)throw new JSONException("VERSION");
  return new Wire.Command(j.getString("pair"),j.getString("id"),j.getLong("issued"),j.getLong("expires"),j.getString("type"),j.getString("mode"),j.getString("mac"));
 }
 public static JSONObject receipt(Wire.Receipt r)throws JSONException{return new JSONObject().put("v",1).put("pair",r.pair).put("id",r.id).put("commandMac",r.commandMac).put("at",r.at).put("stage",r.stage).put("vehicle",r.vehicle).put("mac",r.mac);}
 public static Wire.Receipt receipt(JSONObject j)throws JSONException{
  if(j.getInt("v")!=1)throw new JSONException("VERSION");
  return new Wire.Receipt(j.getString("pair"),j.getString("id"),j.getString("commandMac"),j.getLong("at"),j.getString("stage"),j.getString("vehicle"),j.getString("mac"));
 }
}
