package com.fiskerwarm.internet;
import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
/** Keys are encrypted at rest by the non-exportable Android Keystore key. Backup is disabled in both manifests. */
public final class Vault {
 private static final String ALIAS="fisker-warm-stage6-config";
 private static javax.crypto.SecretKey key()throws Exception{
  KeyStore s=KeyStore.getInstance("AndroidKeyStore");s.load(null);
  if(!s.containsAlias(ALIAS)){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
  return (javax.crypto.SecretKey)s.getKey(ALIAS,null);
 }
 public static void save(Context c,String json)throws Exception{
  Cipher x=Cipher.getInstance("AES/GCM/NoPadding");x.init(Cipher.ENCRYPT_MODE,key());
  String value=Base64.encodeToString(x.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(x.doFinal(json.getBytes("UTF-8")),Base64.NO_WRAP);
  if(!c.getSharedPreferences("vault",0).edit().putString("config",value).commit())throw new Exception("CONFIG_STORAGE");
 }
 public static Config load(Context c,String role)throws Exception{
  String value=c.getSharedPreferences("vault",0).getString("config",null);if(value==null)return null;
  String[] a=value.split(":",2);Cipher x=Cipher.getInstance("AES/GCM/NoPadding");x.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(a[0],Base64.NO_WRAP)));
  return new Config(new String(x.doFinal(Base64.decode(a[1],Base64.NO_WRAP)),"UTF-8"),role);
 }
 public static void clear(Context c){c.getSharedPreferences("vault",0).edit().clear().commit();}
}
