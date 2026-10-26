package com.alexspec.atari;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Typeface;
import android.widget.*;
import com.alexspec.atari.core.*;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Development CPU/memory inspector. Video/audio chips and game loading are not implemented. */
public final class InspectorActivity extends Activity {
    private final Atari65XE machine=new Atari65XE();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView memory,status;
    private volatile int page;
    private boolean busy;
    private volatile boolean destroyed;
    private static final int OS=1, BASIC=2;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(state!=null) page=state.getInt("page",0)&0xff00;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(12*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        TextView heading=new TextView(this);heading.setText("Atari 65XE · процессор и память");heading.setTextSize(19);root.addView(heading);
        TextView note=new TextView(this);
        note.setText("Разработка: 6502 и память. ANTIC/GTIA/POKEY ещё нет — игры и экран Atari пока не работают. ПЗУ OS: 16 КБ, BASIC: 8 КБ (свои файлы).");
        root.addView(note);
        LinearLayout rom=row(root);button(rom,"ПЗУ OS…",()->pick(OS));button(rom,"BASIC…",()->pick(BASIC));
        LinearLayout execution=row(root);
        button(execution,"Сброс",()->task(()->{if(machine.hasOs()) machine.reset();}));
        button(execution,"+1 команда",()->step(1));button(execution,"+100",()->step(100));
        status=new TextView(this);status.setTypeface(Typeface.MONOSPACE);status.setTextSize(13);root.addView(status);
        LinearLayout nav=row(root);
        button(nav,"◀ −256",()->{page=(page-256)&0xff00;task(()->{});});
        button(nav,"К PC",()->task(()->{page=machine.cpu.pc&0xff00;}));
        button(nav,"+256 ▶",()->{page=(page+256)&0xff00;task(()->{});});
        ScrollView scroll=new ScrollView(this);memory=new TextView(this);memory.setTextSize(11);memory.setTypeface(Typeface.MONOSPACE);
        scroll.addView(memory);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        task(()->{
            File os=file(OS),basic=file(BASIC);
            if(os.exists()) machine.loadOs(read(new FileInputStream(os),16384));
            if(basic.exists()) machine.loadBasic(read(new FileInputStream(basic),8192));
            machine.reset();
        });
    }
    private LinearLayout row(LinearLayout root) { LinearLayout row=new LinearLayout(this);root.addView(row);return row; }
    private void button(LinearLayout row,String label,Runnable click) {
        Button b=new Button(this);b.setText(label);b.setTextSize(12);
        b.setOnClickListener(v->{if(!busy) click.run();});row.addView(b,new LinearLayout.LayoutParams(0,-2,1));
    }
    private void pick(int type) {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent,type);
    }
    private File file(int type) { return new File(getFilesDir(),type==OS ? "os.rom":"basic.rom"); }
    private static byte[] read(InputStream source,int size) throws IOException {
        if(source==null) throw new IOException("Не удалось открыть файл");
        try(InputStream in=source;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1) {out.write(buf,0,n);if(out.size()>size) break;}
            if(out.size()!=size) throw new IOException("Нужен файл ровно "+size+" байт");
            return out.toByteArray();
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null || (request!=OS && request!=BASIC)) return;
        Uri uri=data.getData();
        task(()->{
            byte[] rom=read(getContentResolver().openInputStream(uri),request==OS ? 16384:8192);
            try(FileOutputStream out=new FileOutputStream(file(request))) {out.write(rom);}
            if(request==OS) machine.loadOs(rom);else machine.loadBasic(rom);
            machine.reset();page=machine.cpu.pc&0xff00;
        });
    }
    private void step(int count) {
        task(()->{for(int i=0;i<count;i++) machine.stepInstruction();});
    }
    interface Work {void run() throws Exception;}
    private void task(Work work) {
        if(busy || destroyed) return;
        busy=true;
        worker.execute(()->{
            String error=null;
            try {work.run();} catch(Exception e) {error=e.getMessage();}
            String dump=dump(),regs=registers(),message=error;
            runOnUiThread(()->{
                if(destroyed) return;
                busy=false;memory.setText(dump);status.setText(regs);
                if(message!=null) new AlertDialog.Builder(this).setTitle("Atari 65XE").setMessage(message).setPositiveButton("OK",null).show();
            });
        });
    }
    private String registers() {
        M6502 c=machine.cpu;
        return String.format(Locale.US,"OS %s · BASIC %s · PORTB %02X\nPC %04X  A %02X X %02X Y %02X\nSP %02X P %02X  NV-BDIZC\nТакты %d · команды %d\nКод: %02X %02X %02X",
                machine.hasOs()?"✓":"—",machine.hasBasic()?"✓":"—",machine.portb(),c.pc,c.a,c.x,c.y,c.sp,c.p,c.cycles,c.instructions,
                machine.read(c.pc),machine.read(c.pc+1),machine.read(c.pc+2));
    }
    private String dump() {
        StringBuilder s=new StringBuilder(String.format(Locale.US,"$%04X — %s\n\n",page,machine.region(page)));
        for(int i=0;i<256;i+=16) {
            s.append(String.format(Locale.US,"%04X  ",(page+i)&65535));
            for(int j=0;j<16;j++) s.append(String.format(Locale.US,"%02X ",machine.read(page+i+j)));
            s.append('\n');
        }
        return s.toString();
    }
    @Override protected void onSaveInstanceState(Bundle state) {state.putInt("page",page);super.onSaveInstanceState(state);}
    @Override protected void onDestroy() {destroyed=true;worker.shutdownNow();super.onDestroy();}
}
