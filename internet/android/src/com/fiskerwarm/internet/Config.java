package com.fiskerwarm.internet;
import org.json.*;
import java.net.URI;
public final class Config {
 public final String pair,role,token,siteToken,relay;
 public final byte[] key;
 public Config(String json,String expectedRole)throws Exception{
  JSONObject j=new JSONObject(json);
  if(j.getInt("v")!=1||!Wire.MODE.equals(j.getString("mode"))||!expectedRole.equals(j.getString("role")))throw new Exception("CONFIG_ROLE_OR_MODE");
  pair=j.getString("pair");role=expectedRole;token=j.getString("token");siteToken=j.getString("siteToken");relay=j.getString("relay");key=Wire.decode(j.getString("macKey"));
  URI u=new URI(relay);
  if(!Wire.hex(pair,32)||!Wire.hex(token,64)||siteToken.length()<20||siteToken.length()>8192||!siteToken.matches("[A-Za-z0-9_~.+/=-]+")
   ||!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null
   ||(u.getPort()!=-1&&u.getPort()!=443)||!(u.getPath().isEmpty()||u.getPath().equals("/")))throw new Exception("CONFIG_FORMAT");
 }
}
