package net.programmierecke.radiodroid2;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import net.programmierecke.radiodroid2.data.MPDServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

interface IMPDClientStatusChange{
    void changed();
}

public class MPDClient {
    static final String TAG = "MPD";
    public static boolean connected;
    public static boolean isPlaying;

    public static int StringToInt(String str, int defaultValue){
        try{
            return Integer.parseInt(str);
        }
        catch (Exception e){
            Log.e(TAG, "could not parse integer " + str, e);
        }
        return defaultValue;
    }

    public static void Connect(IMPDClientStatusChange listener){
        connected = true;
        if(listener != null)
            listener.changed();
    }

    public static void Disconnect(Context context, IMPDClientStatusChange listener){
        connected = false;
        isPlaying = false;
        if(listener != null)
            listener.changed();
        // Don't stop playback in that case. User can listen via MPD and via the app
        // User can stop playback via MPD with pause button
        //Stop(context);
    }

    public static void Play(final String url, final Context context) {
        final List<MPDServer> servers = Utils.getMPDServers(context);

        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                String mpd_hostname = "";
                int mpd_port = 0;

                for (MPDServer server: servers) {
                    if(server.selected) {
                        mpd_hostname = server.hostname.trim();
                        mpd_port = server.port;
                        break;
                    }
                }
                if(mpd_port == 0)
                    return null;

                return PlayRemoteMPD(mpd_hostname, mpd_port, url);
            }

            @Override
            protected void onPostExecute(Boolean result) {
                super.onPostExecute(result);
            }
        }.execute();
    }

    static boolean discovered = false;
    static Thread t = null;
    static boolean discoveryActive = false;

    public static boolean Discovered(){
        return discovered;
    }

    public static void StartDiscovery(final Context context, final IMPDClientStatusChange listener){
        if (t == null) {
            discoveryActive = true;
            t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while(discoveryActive){
                        try {
                            List<MPDServer> servers = Utils.getMPDServers(context);
                            boolean hasOneOrMoreConnections = false;
                            for (MPDServer server: servers) {
                                final String mpd_hostname = server.hostname.trim();
                                final int mpd_port = server.port;

                                if (!"".equals(mpd_hostname)) {
                                    boolean isConnected = CheckConnection(mpd_hostname, mpd_port);
                                    server.connected = isConnected;
                                    hasOneOrMoreConnections = hasOneOrMoreConnections? hasOneOrMoreConnections : isConnected;
                                }
                            }
                            Utils.saveMPDServers(servers, context);
                            SetDiscoveredStatus(hasOneOrMoreConnections, listener);
                            // check every 5 seconds
                            Thread.sleep(5*1000);
                        } catch (Exception e) {
                            SetDiscoveredStatus(false, listener);
                        }
                    }
                    SetDiscoveredStatus(false, listener);
                    t = null;
                }
            });
            t.start();
        }
    }

    private static void SetDiscoveredStatus(boolean status, IMPDClientStatusChange listener){
        if (status != discovered){
            discovered = status;
            if(listener != null)
                listener.changed();
        }
    }

    private static boolean CheckConnection(String mpd_hostname, int mpd_port) {
        Boolean result = false;

        try {
            if(BuildConfig.DEBUG) { Log.d(TAG, "Check connection..."); }
            Socket s = new Socket();
            // If you'll create the socket with default constructor new Socket(hostname, port) you will get 3 min timeout if the server is unreachable
            s.connect(new InetSocketAddress(mpd_hostname, mpd_port), 5*1000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));
            String info = reader.readLine();
            if(BuildConfig.DEBUG) { Log.d(TAG, info); }
            if (info.startsWith("OK")){
                result = true;
            }
            reader.close();
            writer.close();
            s.close();
        } catch (Exception e) {
            Log.e(TAG,e.toString());
        }
        if(BuildConfig.DEBUG) { Log.d(TAG, "Connection status:"+result); }
        return result;
    }


    public static void Stop(final Context context) {
        final List<MPDServer> servers = Utils.getMPDServers(context);

        new Thread(new Runnable() {
            @Override
            public void run() {
                for (MPDServer server: servers) {
                    if(server.selected) {
                        final String mpd_hostname = server.hostname.trim();
                        final int mpd_port = server.port;

                        StopInternal(mpd_hostname, mpd_port);
                        break;
                    }
                }
            }
        }).start();
    }

    private static boolean StopInternal(String mpd_hostname, int mpd_port) {
        Boolean result = false;
        isPlaying = false;
        try {
            if(BuildConfig.DEBUG) { Log.d(TAG, "Check connection before stop..."); }
            Socket s = new Socket();
            s.connect(new InetSocketAddress(mpd_hostname, mpd_port), 15*1000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));
            String info = reader.readLine();
            if(BuildConfig.DEBUG) { Log.d(TAG, info); }
            if (info.startsWith("OK")) {
                String cmd = "stop";
                writer.write(cmd);
                writer.newLine();
                writer.flush();
                result = true;
            }
            reader.close();
            writer.close();
            s.close();
        } catch (Exception e) {
            Log.e(TAG,e.toString());
        }
        if(BuildConfig.DEBUG) { Log.d(TAG, "Connection status:"+result); }
        return result;
    }

    public static void StopDiscovery(IMPDClientStatusChange listener){
        discoveryActive = false;
        SetDiscoveredStatus(false, listener);
        t = null;
    }

    private static Boolean PlayRemoteMPD(String mpd_hostname, int mpd_port, String url){
        Boolean result = false;
        isPlaying = true;
        try {
            if(BuildConfig.DEBUG) { Log.d("MPD", "Start"); }
            Socket s = new Socket();
            s.connect(new InetSocketAddress(mpd_hostname, mpd_port), 5*1000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));
            String info = reader.readLine();
            if(BuildConfig.DEBUG) { Log.d("MPD", info); }
            if (info.startsWith("OK")){
                String cmd = "addid "+url;
                writer.write(cmd);
                writer.newLine();
                writer.flush();

                info = reader.readLine();
                if(BuildConfig.DEBUG) { Log.d("MPD", info); }
                if (info.startsWith("Id:")){
                    int songid = Integer.parseInt(info.substring(3).trim());
                    cmd = "playid "+songid;
                    writer.write(cmd);
                    writer.newLine();
                    writer.flush();
                    if(BuildConfig.DEBUG) { Log.d("MPD", "OK"); }
                    result = true;
                }
            }
            reader.close();
            writer.close();
            s.close();
        } catch (Exception e) {
            Log.e("MPD",e.toString());
        }
        return result;
    }

    public static void SetVolume(final String mpd_hostname, final int mpd_port, final int volume) {
        // Volume can be +something or -something

        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                try {
                    if(BuildConfig.DEBUG) { Log.d("MPD", "Start"); }
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(mpd_hostname, mpd_port), 5*1000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));
                    String info = reader.readLine();
                    if(BuildConfig.DEBUG) { Log.d("MPD", info); }
                    if (info.startsWith("OK")){
                        String cmd = "status";
                        writer.write(cmd);
                        writer.newLine();
                        writer.flush();

                        info = reader.readLine();
                        if(BuildConfig.DEBUG) { Log.d("MPD volume", info); }
                        if (info.startsWith("volume:")){
                            int currentVolume = Integer.parseInt(info.substring(8).trim());
                            int newVolume = currentVolume + volume;
                            // Max == 100, min == 0
                            newVolume = Math.min(newVolume,100);
                            newVolume = Math.max(newVolume,0);
                            cmd = "setvol "+ newVolume;
                            writer.write(cmd);
                            writer.newLine();
                            writer.flush();
                            if(BuildConfig.DEBUG) { Log.d("MPD", "OK"); }
                        }
                    }
                    reader.close();
                    writer.close();
                    s.close();
                } catch (Exception e) {
                    Log.e("MPD",e.toString());
                }
                return null;
            }

            @Override
            protected void onPostExecute(Boolean result) {
                super.onPostExecute(result);
            }
        }.execute();
    }

    public static boolean Connected() {
        return connected;
    }
}
