package com.mg4.store;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Icone delle app: scaricate dal catalogo una volta sola e tenute in cache (si rinnovano quando cambia l'impronta). */
final class Icons {
    private Icons() {}

    /** Carica le icone mancanti; ritorna true se ne ha caricata almeno una. Da chiamare fuori dal thread principale. */
    static boolean load(Context c, Api api, List<Item> items) {
        File dir = new File(c.getFilesDir(), "icone");
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        boolean any = false;
        for (Item it : items) {
            if (it.bmp != null || it.icon.isEmpty()) continue;
            File f = new File(dir, it.id + "-" + it.iconSha + ".png");
            try {
                if (!f.isFile()) {
                    byte[] png = api.download(it.icon, null);
                    // toglie le icone vecchie della stessa app
                    File[] old = dir.listFiles((d, n) -> n.startsWith(it.id + "-"));
                    if (old != null) for (File o : old) o.delete();
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(png);
                    }
                }
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b != null) {
                    it.bmp = b;
                    any = true;
                } else {
                    f.delete();
                }
            } catch (Exception ignored) {
                // senza icona si usa il simbolo predefinito
            }
        }
        return any;
    }
}
