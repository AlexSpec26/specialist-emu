package com.alexspec.atari;

/** Desktop JNI test against the same bridge sources used by the Android APK. */
public final class AtariJniTest {
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args) {
        check(NativeAtari.open("/tmp/atari-native-system",args[0],false),"JNI open");
        int[] picture=new int[120000],geometry=new int[2];short[] audio=new short[8192];byte[] keys=new byte[512];
        for(int i=0;i<400;i++)check(NativeAtari.frame(picture,audio,geometry,0,keys)>0,"JNI frame/audio");
        check(geometry[0]==336 && geometry[1]==240,"JNI geometry");
        byte[] ram=NativeAtari.memory();check(ram!=null && ram.length==65536 && ram[0x81]==4,"JNI memory");
        for(int i=0;i<16;i++)NativeAtari.frame(picture,audio,geometry,1<<7,keys);
        check((NativeAtari.memory()[0x81]&255)>4,"JNI controls");
        byte[] state=NativeAtari.save();check(state!=null && state.length>10000,"JNI save");
        check(NativeAtari.load(state),"JNI load");
        NativeAtari.reset();NativeAtari.close();System.out.println("ANDROID JNI BRIDGE OK (host JVM)");
    }
}
