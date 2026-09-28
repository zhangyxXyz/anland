package com.anland.shell.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Runs with app_process against the built APK: real Android Bitmap/Canvas,
 * not JVM stubs. Covers the missing/uneven icon regressions. */
public final class IconRenderingProbe {
    public static void main(String[] args) throws Exception {
        Method rank=IconLoader.class.getDeclaredMethod("pickBest",java.util.List.class);
        rank.setAccessible(true);
        String tiny="/usr/share/icons/hicolor/16x16/apps/example.png";
        String vector="/usr/share/icons/hicolor/scalable/apps/example.svg";
        require(vector.equals(rank.invoke(null,Arrays.asList(tiny,vector))),"vector beats tiny raster");
        require(vector.equals(rank.invoke(null,Arrays.asList(vector,tiny))),"ranking is traversal independent");
        Bitmap empty=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        require(IconLoader.normalize(empty)==null,"blank icon rejected");
        Bitmap padded=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        for(int y=48;y<80;y++)for(int x=32;x<96;x++)padded.setPixel(x,y,Color.RED);
        Bitmap normalized=IconLoader.normalize(padded);
        int left=192,right=-1,top=192,bottom=-1;
        for(int y=0;y<192;y++)for(int x=0;x<192;x++)if(Color.alpha(normalized.getPixel(x,y))>=128){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
        require(Math.abs((right-left+1f)/(bottom-top+1f)-2f)<.04f,"aspect ratio preserved");
        require(Math.abs(left-(191-right))<=1&&Math.abs(top-(191-bottom))<=1,"content centered");
        require(right-left>168&&right-left<176,"transparent padding removed");
        require(!IconLoader.isMonochrome(normalized),"colored artwork is not tinted");
        Bitmap glyph=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
        glyph.setPixel(12,12,Color.BLACK);
        require(IconLoader.isMonochrome(glyph),"neutral glyph gets theme contrast");
        require(IconLoader.letterTile("\uD83D\uDDA5 test",192)!=null,"Unicode placeholder supported");
        System.out.println("PASS: ranking, empty decode, optical bounds, aspect ratio, centering, Unicode fallback");
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
