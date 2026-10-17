package dev.alex.afptorrent;
import dev.alex.afptorrent.ITorrentCallback;
interface ITorrentService {
 int apiVersion();
 String capabilities();
 String snapshot();
 String detail(String id);
 String command(String operation, String arguments);
 void subscribe(ITorrentCallback callback);
 void unsubscribe(ITorrentCallback callback);
}
