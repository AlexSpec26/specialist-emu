import com.alexspec.specialist.core.SpecKeys;
import com.alexspec.specialist.core.Specialist;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.imageio.ImageIO;

/**
 * Desktop smoke test: boots a ROM, optionally loads an .rks, runs frames, presses keys and
 * writes screenshots.
 * Usage: java MachineTest rom out_prefix [file.rks] [script]
 * script: comma separated items: "f120" run 120 frames, "k:LABEL:frames" hold a key, "s" screenshot,
 *         "c3" set colour mode 3
 */
public class MachineTest {
    public static void main(String[] args) throws Exception {
        Specialist m = new Specialist();
        m.loadRom(Files.readAllBytes(Paths.get(args[0])));
        m.hardReset();
        String prefix = args[1];
        short[] audio = new short[2000];
        int[] pix = new int[Specialist.WIDTH * Specialist.HEIGHT];
        int shot = 0;
        int color = Specialist.COLOR_WHITE;
        long t0 = System.nanoTime();
        int frames = 0;
        for (int i = 0; i < 50; i++) { m.runFrame(audio); frames++; }
        if (args.length > 2 && !args[2].equals("-")) {
            Specialist.RksInfo info = m.loadRks(Files.readAllBytes(Paths.get(args[2])), true);
            System.out.printf("loaded %04X-%04X checksum %s%n", info.start, info.end, info.checksumOk ? "ok" : "BAD");
        }
        String script = args.length > 3 ? args[3] : "f100,s";
        int beepSamples = 0;
        for (String item : script.split(",")) {
            if (item.startsWith("f")) {
                int n = Integer.parseInt(item.substring(1));
                for (int i = 0; i < n; i++) {
                    int c = m.runFrame(audio);
                    for (int k = 0; k < c; k++) if (Math.abs(audio[k]) > 500) beepSamples++;
                    frames++;
                }
            } else if (item.startsWith("k:")) {
                String[] p = item.split(":");
                int code = SpecKeys.byLabel(p[1]);
                if (code < 0) throw new IllegalArgumentException("no key " + p[1]);
                m.keyDown(code);
                int n = p.length > 2 ? Integer.parseInt(p[2]) : 5;
                for (int i = 0; i < n; i++) { m.runFrame(audio); frames++; }
                m.keyUp(code);
                for (int i = 0; i < 5; i++) { m.runFrame(audio); frames++; }
            } else if (item.startsWith("c")) {
                color = Integer.parseInt(item.substring(1));
            } else if (item.equals("s")) {
                m.render(pix, color);
                BufferedImage img = new BufferedImage(Specialist.WIDTH, Specialist.HEIGHT, BufferedImage.TYPE_INT_RGB);
                img.setRGB(0, 0, Specialist.WIDTH, Specialist.HEIGHT, pix, 0, Specialist.WIDTH);
                File f = new File(prefix + "_" + (shot++) + ".png");
                ImageIO.write(img, "png", f);
                System.out.println("screenshot " + f + "  pc=" + Integer.toHexString(m.cpu.pc));
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("%d frames in %.2fs (%.1fx realtime), loud samples=%d%n", frames, secs, frames / 50.0 / secs, beepSamples);
    }
}
