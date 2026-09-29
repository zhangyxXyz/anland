package com.anlandnext.awl;
import java.util.Arrays;

public class WindowSafeAreaTest {
    static void equal(int[] actual, int... expected) {
        if (!Arrays.equals(actual, expected)) throw new AssertionError(Arrays.toString(actual));
    }
    public static void main(String[] args) {
        // A 3200x2136 tablet with 54px corners used to lose 16px on each side.
        // A surface is now bounded by obstructions only, for all client types;
        // screen radius, orientation and late app identity cannot shrink it.
        equal(WindowSafeArea.contentMargins(new int[4],0,false),0,0,0,0);
        equal(WindowSafeArea.contentMargins(new int[4],0,true),0,0,0,0);

        // A real cutout/status bar stays protected in either IME mode.
        int[] obstructions = {32,80,24,40};
        equal(WindowSafeArea.contentMargins(obstructions,600,false),32,80,24,600);
        equal(WindowSafeArea.contentMargins(obstructions,600,true),32,80,24,40);
        // A floating keyboard must not reduce the existing bottom obstruction.
        equal(WindowSafeArea.contentMargins(obstructions,20,false),32,80,24,40);
        // Keyboard dismissal restores the viewport; calculations never mutate
        // the caller's system insets or share a mutable result array.
        int[] withIme = WindowSafeArea.contentMargins(obstructions,600,false);
        withIme[0] = 99;
        equal(obstructions,32,80,24,40);
        equal(WindowSafeArea.contentMargins(obstructions,0,false),32,80,24,40);
        System.out.println("window safe area tests passed");
    }
}
