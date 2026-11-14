#!/usr/bin/env python3
"""Genera app/res/values*/strings.xml con i testi dello Store in 8 lingue.
Ogni voce: chiave -> (it, en, es, de, pt, fr, tr, pl). L'inglese e' la lingua di riserva (cartella values/).
Uso: python3 tools/i18n.py   (lo lancia anche build.sh)
"""
import os
from xml.sax.saxutils import escape

LANGS = ["it", "en", "es", "de", "pt", "fr", "tr", "pl"]

S = {
    "app_name": ("Store",) * 8,
    "apps_count": ("%1$d app", "%1$d apps", "%1$d apps", "%1$d Apps", "%1$d apps", "%1$d applis", "%1$d uygulama", "%1$d aplikacji"),
    "updates_count": ("%1$d da aggiornare", "%1$d to update", "%1$d por actualizar", "%1$d zu aktualisieren", "%1$d por atualizar", "%1$d à mettre à jour", "%1$d güncelleme", "%1$d do aktualizacji"),
    "loading": ("Caricamento…", "Loading…", "Cargando…", "Wird geladen…", "A carregar…", "Chargement…", "Yükleniyor…", "Ładowanie…"),
    "not_mg4": ("Questo dispositivo non è un'infotainment MG4", "This device isn't an MG4 infotainment", "Este dispositivo no es un infoentretenimiento MG4", "Dieses Gerät ist kein MG4-Infotainment", "Este dispositivo não é um infoentretenimento MG4", "Cet appareil n'est pas un infodivertissement MG4", "Bu cihaz bir MG4 bilgi-eğlence sistemi değil", "To urządzenie nie jest systemem multimedialnym MG4"),
    "not_mg4_sub": ("Lo store funziona solo sull'auto.", "The store only works in the car.", "La tienda solo funciona en el coche.", "Der Store funktioniert nur im Auto.", "A loja só funciona no carro.", "La boutique ne fonctionne que dans la voiture.", "Mağaza yalnızca araçta çalışır.", "Sklep działa tylko w samochodzie."),
    "blocked": ("Questa auto non è abilitata allo store", "This car isn't enabled for the store", "Este coche no está habilitado para la tienda", "Dieses Auto ist für den Store nicht freigeschaltet", "Este carro não está ativado para a loja", "Cette voiture n'est pas autorisée sur la boutique", "Bu araç mağaza için etkin değil", "Ten samochód nie ma dostępu do sklepu"),
    "offline": ("Nessuna connessione", "No connection", "Sin conexión", "Keine Verbindung", "Sem ligação", "Pas de connexion", "Bağlantı yok", "Brak połączenia"),
    "offline_sub": ("Controlla la rete dell'auto e riprova.", "Check the car's network and try again.", "Comprueba la red del coche e inténtalo de nuevo.", "Prüfe das Netz des Autos und versuche es erneut.", "Verifica a rede do carro e tenta novamente.", "Vérifiez le réseau de la voiture et réessayez.", "Aracın ağını kontrol edip tekrar deneyin.", "Sprawdź sieć samochodu i spróbuj ponownie."),
    "error": ("Lo store non risponde", "The store isn't responding", "La tienda no responde", "Der Store antwortet nicht", "A loja não responde", "La boutique ne répond pas", "Mağaza yanıt vermiyor", "Sklep nie odpowiada"),
    "error_sub": ("Riprova tra qualche minuto.", "Try again in a few minutes.", "Inténtalo de nuevo en unos minutos.", "Versuche es in ein paar Minuten erneut.", "Tenta novamente daqui a alguns minutos.", "Réessayez dans quelques minutes.", "Birkaç dakika sonra tekrar deneyin.", "Spróbuj ponownie za kilka minut."),
    "empty": ("Nessuna app disponibile per questa auto", "No apps available for this car", "No hay apps disponibles para este coche", "Keine Apps für dieses Auto verfügbar", "Nenhuma app disponível para este carro", "Aucune appli disponible pour cette voiture", "Bu araç için uygulama yok", "Brak aplikacji dla tego samochodu"),
    "retry": ("Riprova", "Try again", "Reintentar", "Erneut versuchen", "Tentar novamente", "Réessayer", "Tekrar dene", "Spróbuj ponownie"),
    "install": ("Installa", "Install", "Instalar", "Installieren", "Instalar", "Installer", "Yükle", "Zainstaluj"),
    "update": ("Aggiorna", "Update", "Actualizar", "Aktualisieren", "Atualizar", "Mettre à jour", "Güncelle", "Aktualizuj"),
    "installed": ("Installata", "Installed", "Instalada", "Installiert", "Instalada", "Installée", "Yüklü", "Zainstalowana"),
    "installing": ("Installazione…", "Installing…", "Instalando…", "Wird installiert…", "A instalar…", "Installation…", "Yükleniyor…", "Instalowanie…"),
    "failed": ("Installazione non riuscita", "Installation failed", "La instalación falló", "Installation fehlgeschlagen", "A instalação falhou", "Échec de l'installation", "Yükleme başarısız", "Instalacja nie powiodła się"),
    "confirm_needed": ("Conferma l'installazione sullo schermo", "Confirm the installation on screen", "Confirma la instalación en pantalla", "Bestätige die Installation auf dem Bildschirm", "Confirma a instalação no ecrã", "Confirmez l'installation à l'écran", "Yüklemeyi ekranda onaylayın", "Potwierdź instalację na ekranie"),
    "version": ("Versione %1$s", "Version %1$s", "Versión %1$s", "Version %1$s", "Versão %1$s", "Version %1$s", "Sürüm %1$s", "Wersja %1$s"),
    "installed_ver": ("Sull'auto: %1$s", "In the car: %1$s", "En el coche: %1$s", "Im Auto: %1$s", "No carro: %1$s", "Dans la voiture : %1$s", "Araçta: %1$s", "W samochodzie: %1$s"),
    "size_mb": ("%1$s MB",) * 5 + ("%1$s Mo", "%1$s MB", "%1$s MB"),
    "whats_new": ("Novità", "What's new", "Novedades", "Neuigkeiten", "Novidades", "Nouveautés", "Yenilikler", "Nowości"),
    "no_notes": ("Nessuna nota per questa versione.", "No notes for this version.", "No hay notas para esta versión.", "Keine Hinweise zu dieser Version.", "Sem notas para esta versão.", "Aucune note pour cette version.", "Bu sürüm için not yok.", "Brak informacji o tej wersji."),
    "open": ("Apri", "Open", "Abrir", "Öffnen", "Abrir", "Ouvrir", "Aç", "Otwórz"),
    "close": ("Chiudi", "Close", "Cerrar", "Schließen", "Fechar", "Fermer", "Kapat", "Zamknij"),
    "settings": ("Impostazioni", "Settings", "Ajustes", "Einstellungen", "Definições", "Paramètres", "Ayarlar", "Ustawienia"),
    "language": ("Lingua", "Language", "Idioma", "Sprache", "Idioma", "Langue", "Dil", "Język"),
    "lang_auto": ("Come l'auto", "Same as car", "Como el coche", "Wie das Auto", "Como o carro", "Comme la voiture", "Araçla aynı", "Jak w samochodzie"),
    "theme": ("Tema", "Theme", "Tema", "Design", "Tema", "Thème", "Tema", "Motyw"),
    "theme_auto": ("Automatico", "Automatic", "Automático", "Automatisch", "Automático", "Automatique", "Otomatik", "Automatyczny"),
    "theme_day": ("Giorno", "Day", "Día", "Tag", "Dia", "Jour", "Gündüz", "Dzień"),
    "theme_night": ("Notte", "Night", "Noche", "Nacht", "Noite", "Nuit", "Gece", "Noc"),
    "unknown_fw": ("Software non riconosciuto", "Unknown software", "Software no reconocido", "Software nicht erkannt", "Software não reconhecido", "Logiciel non reconnu", "Yazılım tanınmadı", "Nieznane oprogramowanie"),
    "updates_available": ("Aggiornamenti disponibili: %1$d", "Updates available: %1$d", "Actualizaciones disponibles: %1$d", "Verfügbare Updates: %1$d", "Atualizações disponíveis: %1$d", "Mises à jour disponibles : %1$d", "Mevcut güncellemeler: %1$d", "Dostępne aktualizacje: %1$d"),
    "update_all": ("Aggiorna tutto", "Update all", "Actualizar todo", "Alle aktualisieren", "Atualizar tudo", "Tout mettre à jour", "Tümünü güncelle", "Aktualizuj wszystko"),
    "notif_channel": ("Aggiornamenti delle app", "App updates", "Actualizaciones de apps", "App-Updates", "Atualizações de apps", "Mises à jour des applis", "Uygulama güncellemeleri", "Aktualizacje aplikacji"),
    "uninstall": ("Disinstalla", "Uninstall", "Desinstalar", "Deinstallieren", "Desinstalar", "Désinstaller", "Kaldır", "Odinstaluj"),
    "uninstall_confirm": ("Disinstallare %1$s?", "Uninstall %1$s?", "¿Desinstalar %1$s?", "%1$s deinstallieren?", "Desinstalar %1$s?", "Désinstaller %1$s ?", "%1$s kaldırılsın mı?", "Odinstalować %1$s?"),
    "uninstall_sub": ("L'app e i suoi dati vengono tolti dall'auto. Puoi reinstallarla dallo store quando vuoi.", "The app and its data are removed from the car. You can reinstall it from the store whenever you like.", "La app y sus datos se eliminan del coche. Puedes reinstalarla desde la tienda cuando quieras.", "Die App und ihre Daten werden aus dem Auto entfernt. Du kannst sie jederzeit über den Store neu installieren.", "A app e os seus dados são removidos do carro. Podes reinstalá-la a partir da loja quando quiseres.", "L'appli et ses données sont supprimées de la voiture. Vous pouvez la réinstaller depuis la boutique quand vous voulez.", "Uygulama ve verileri araçtan silinir. İstediğiniz zaman mağazadan yeniden yükleyebilirsiniz.", "Aplikacja i jej dane zostaną usunięte z samochodu. Możesz ją ponownie zainstalować ze sklepu w dowolnej chwili."),
    "uninstalling": ("Disinstallazione…", "Uninstalling…", "Desinstalando…", "Wird deinstalliert…", "A desinstalar…", "Désinstallation…", "Kaldırılıyor…", "Odinstalowywanie…"),
    "uninstall_failed": ("Disinstallazione non riuscita", "Uninstall failed", "La desinstalación falló", "Deinstallation fehlgeschlagen", "A desinstalação falhou", "Échec de la désinstallation", "Kaldırma başarısız", "Odinstalowanie nie powiodło się"),
    "cancel": ("Annulla", "Cancel", "Cancelar", "Abbrechen", "Cancelar", "Annuler", "İptal", "Anuluj"),
    "store_new": ("Nuova versione dello Store", "New version of the Store", "Nueva versión de la tienda", "Neue Store-Version", "Nova versão da loja", "Nouvelle version de la boutique", "Mağazanın yeni sürümü", "Nowa wersja sklepu"),
}


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for i, lang in enumerate(LANGS):
        for folder in (["values-" + lang] + (["values"] if lang == "en" else [])):
            d = os.path.join(root, "app", "res", folder)
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, "strings.xml"), "w", encoding="utf-8") as f:
                f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- generato da tools/i18n.py -->\n<resources>\n')
                for k, v in S.items():
                    assert len(v) == 8, k
                    t = escape(v[i]).replace("'", "\\'").replace('"', '\\"')
                    f.write('    <string name="%s" formatted="%s">%s</string>\n' % (k, "true" if "%1$" in v[i] else "false", t))
                f.write("</resources>\n")
    print("testi generati:", len(S), "voci x", len(LANGS), "lingue")


if __name__ == "__main__":
    main()
