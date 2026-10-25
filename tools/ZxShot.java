import com.alexspec.zx.core.Spectrum48;
import com.alexspec.zx.core.Tape;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Desktop check of the Spectrum: loads a game (TAP/TZX autoload, or SNA/Z80), runs it and saves
 * screenshots. Usage: java ZxShot 48k.rom game out_prefix [real] [step:frames[:keys]] ...
 * keys: comma separated key codes held during the step; "k" prefix = kempston bits.
 */
public class ZxShot {
    public static void main(String[] a) throws Exception {
        Spectrum48 zx = new Spectrum48(Files.readAllBytes(Paths.get(a[0])));
        String g = a[1].toLowerCase();
        byte[] f = Files.readAllBytes(Paths.get(a[1]));
        int argi = 3;
        boolean real = a.length > 3 && a[3].equals("real");
        if (real) argi++;
        if (g.endsWith(".tap") || g.endsWith(".tzx")) {
            Tape t = g.endsWith(".tap") ? Tape.fromTap(f) : Tape.fromTzx(f);
            for (Tape.Block b : t.blocks) System.out.println("  block " + b.name);
            t.rewind();
            zx.flashLoad = !real;
            zx.tape = t;
            zx.autoLoad();
        } else if (g.endsWith(".sna")) zx.loadSna(f);
        else zx.loadZ80(f);
        int[] pix = new int[Spectrum48.WIDTH * Spectrum48.HEIGHT];
        short[] audio = new short[2000];
        int shot = 0;
        long t0 = System.nanoTime();
        int total = 0;
        for (; argi < a.length; argi++) {
            String[] p = a[argi].split(":");
            int frames = Integer.parseInt(p[1]);
            int[] keys = p.length > 2 && !p[2].isEmpty() && !p[2].startsWith("k") ? java.util.Arrays.stream(p[2].split(",")).mapToInt(Integer::parseInt).toArray() : new int[0];
            if (p.length > 2 && p[2].startsWith("k")) zx.kempston = Integer.parseInt(p[2].substring(1));
            for (int k : keys) zx.keyDown(k);
            for (int i = 0; i < frames; i++) zx.runFrame(audio);
            System.arraycopy(zx.screen, 0, pix, 0, pix.length);
            total += frames;
            for (int k : keys) zx.keyUp(k);
            zx.kempston = 0;
            if (p[0].equals("shot")) {
                BufferedImage img = new BufferedImage(Spectrum48.WIDTH, Spectrum48.HEIGHT, BufferedImage.TYPE_INT_RGB);
                img.setRGB(0, 0, Spectrum48.WIDTH, Spectrum48.HEIGHT, pix, 0, Spectrum48.WIDTH);
                BufferedImage big = new BufferedImage(Spectrum48.WIDTH * 2, Spectrum48.HEIGHT * 2, BufferedImage.TYPE_INT_RGB);
                big.getGraphics().drawImage(img, 0, 0, Spectrum48.WIDTH * 2, Spectrum48.HEIGHT * 2, null);
                String name = a[2] + "_" + (shot++) + ".png";
                javax.imageio.ImageIO.write(big, "png", new File(name));
                System.out.println("saved " + name + " frame " + total + " pc=" + Integer.toHexString(zx.cpu.pc)
                        + (zx.tape != null ? " tape " + (int) (zx.tape.progress() * 100) + "% playing=" + zx.tape.playing : ""));
            }
        }
        System.out.printf("%d frames in %.2f s%n", total, (System.nanoTime() - t0) / 1e9);
    }
}
