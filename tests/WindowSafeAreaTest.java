package com.anlandnext.awl;
import java.util.Arrays;

public class WindowSafeAreaTest {
    static void equal(int[] actual, int... expected) {
        if (!Arrays.equals(actual, expected)) throw new AssertionError(Arrays.toString(actual));
    }
    public static void main(String[] args) {
        int[][] rounded = {{40,40,40},{960,40,40},{960,660,40},{40,660,40}};
        equal(WindowSafeArea.margins(0,0,1000,700,new int[4],rounded),12,12,12,12);
        // Insets already keep this smaller child away from the physical corners.
        equal(WindowSafeArea.margins(50,50,900,600,new int[4],rounded),0,0,0,0);
        // Keyboard avoidance and top cutout win; no redundant corner margins.
        equal(WindowSafeArea.margins(0,0,1000,700,new int[]{0,60,0,250},rounded),0,60,0,250);
        equal(WindowSafeArea.margins(0,0,1000,700,new int[4],new int[4][]),0,0,0,0);
        // Only the bottom corners intersect when the content starts below the status bar.
        equal(WindowSafeArea.margins(0,60,1000,640,new int[4],rounded),12,0,12,12);
        int[][] asymmetric = {{20,20,20},null,null,null};
        equal(WindowSafeArea.margins(0,0,1000,700,new int[4],asymmetric),6,6,0,0);
        int[][] tablet = {{54,54,54},{3146,54,54},{3146,2082,54},{54,2082,54}};
        equal(WindowSafeArea.contentMargins("org.freedesktop.Xwayland",0,0,3200,2136,new int[4],tablet),0,0,0,0);
        equal(WindowSafeArea.contentMargins("org.freedesktop.Xwayland",0,0,3200,2136,new int[]{0,80,0,600},tablet),0,80,0,600);
        equal(WindowSafeArea.contentMargins("ordinary.app",0,0,3200,2136,new int[4],tablet),16,16,16,16);
        equal(WindowSafeArea.contentMargins(null,0,0,3200,2136,new int[4],tablet),16,16,16,16);
        System.out.println("window safe area tests passed");
    }
}
