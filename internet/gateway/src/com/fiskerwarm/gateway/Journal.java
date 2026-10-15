package com.fiskerwarm.gateway;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import com.fiskerwarm.internet.*;
import java.util.*;
/** SQLite is committed before receipt/acceptance; FULL synchronous mode survives process restart. */
final class Journal extends SQLiteOpenHelper implements GatewayEngine.Journal {
 Journal(Context c){super(c,"gateway-journal.db",null,1);}
 @Override public void onConfigure(SQLiteDatabase db){super.onConfigure(db);db.execSQL("PRAGMA synchronous=FULL");}
 @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE journal(pair TEXT NOT NULL,id TEXT NOT NULL,command TEXT NOT NULL,receipt TEXT NOT NULL,rank INTEGER NOT NULL,acked INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(pair,id))");}
 @Override public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("MIGRATION_REQUIRED");}
 public Wire.Receipt find(String p,String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT receipt FROM journal WHERE pair=? AND id=?",new String[]{p,id})){return c.moveToFirst()?JsonWire.receipt(new org.json.JSONObject(c.getString(0))):null;}catch(org.json.JSONException e){throw new IllegalStateException("JOURNAL_CORRUPT");}}
 public boolean insert(Wire.Command command,Wire.Receipt receipt){try{ContentValues v=new ContentValues();v.put("pair",command.pair);v.put("id",command.id);v.put("command",JsonWire.command(command).toString());v.put("receipt",JsonWire.receipt(receipt).toString());v.put("rank",receipt.rank());return getWritableDatabase().insertWithOnConflict("journal",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;}catch(org.json.JSONException e){throw new IllegalStateException("JOURNAL_FORMAT");}}
 public void replace(Wire.Receipt r){try{ContentValues v=new ContentValues();v.put("receipt",JsonWire.receipt(r).toString());v.put("rank",r.rank());if(getWritableDatabase().update("journal",v,"pair=? AND id=? AND rank<?",new String[]{r.pair,r.id,String.valueOf(r.rank())})!=1)throw new IllegalStateException("JOURNAL_TRANSITION");}catch(org.json.JSONException e){throw new IllegalStateException("JOURNAL_FORMAT");}}
 public List<Wire.Command> interrupted(String pair){List<Wire.Command> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT command FROM journal WHERE pair=? AND rank=1",new String[]{pair})){while(c.moveToNext())result.add(JsonWire.command(new org.json.JSONObject(c.getString(0))));}catch(org.json.JSONException e){throw new IllegalStateException("JOURNAL_CORRUPT");}return result;}
 List<Wire.Receipt> outbox(String pair){List<Wire.Receipt> list=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT receipt FROM journal WHERE pair=? AND acked<rank ORDER BY rowid LIMIT 20",new String[]{pair})){while(c.moveToNext())list.add(JsonWire.receipt(new org.json.JSONObject(c.getString(0))));}catch(org.json.JSONException e){throw new IllegalStateException("JOURNAL_CORRUPT");}return list;}
 void ack(Wire.Receipt r){ContentValues v=new ContentValues();v.put("acked",r.rank());getWritableDatabase().update("journal",v,"pair=? AND id=? AND acked<?",new String[]{r.pair,r.id,String.valueOf(r.rank())});}
 int count(String pair){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM journal WHERE pair=?",new String[]{pair})){c.moveToFirst();return c.getInt(0);}}
}
