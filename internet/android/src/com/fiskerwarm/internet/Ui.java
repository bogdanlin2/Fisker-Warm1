package com.fiskerwarm.internet;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.io.*;
public final class Ui {
 public static final int PICK=41;
 public static LinearLayout page(Activity a,String title){
  a.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
  a.getWindow().setStatusBarColor(Color.rgb(15,32,42));a.getWindow().setNavigationBarColor(Color.rgb(15,32,42));
  ScrollView s=new ScrollView(a);s.setFillViewport(true);s.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});s.setBackgroundColor(Color.rgb(15,32,42));
  LinearLayout p=new LinearLayout(a);p.setOrientation(1);p.setPadding(28,36,28,32);s.addView(p);a.setContentView(s);
  TextView h=text(a,p,title);h.setTextSize(30);h.setTextColor(Color.WHITE);
  TextView mode=text(a,p,"ЕТАП 6 · ТЕСТОВИЙ РЕЖИМ");mode.setTextColor(Color.rgb(255,193,90));return p;
 }
 public static TextView text(Activity a,LinearLayout p,String t){TextView v=new TextView(a);v.setText(t);v.setTextSize(17);v.setTextColor(Color.rgb(219,232,240));v.setPadding(0,12,0,12);p.addView(v);return v;}
 public static Button button(Activity a,LinearLayout p,String t,View.OnClickListener f){
  Button b=new Button(a);b.setText(t);b.setAllCaps(false);
  int[][] states=new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}};
  b.setBackgroundTintList(new android.content.res.ColorStateList(states,new int[]{Color.rgb(48,65,77),Color.rgb(139,211,255)}));
  b.setTextColor(new android.content.res.ColorStateList(states,new int[]{Color.rgb(166,183,195),Color.rgb(8,40,60)}));
  p.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(f);return b;
 }
 public static void pick(Activity a){
  // Samsung/providers can label downloaded JSON as text/plain or octet-stream.
  // Accept a selected document here; Config still validates its full contents and role.
  Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
  a.startActivityForResult(i,PICK);
 }
 public static String imported(Activity a,Intent data)throws Exception{
  if(data==null||data.getData()==null)throw new Exception("NO_DOCUMENT");
  try(InputStream i=a.getContentResolver().openInputStream(data.getData())){return PrivateConfigFile.read(i);}
 }
}
