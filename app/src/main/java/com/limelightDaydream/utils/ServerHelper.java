package com.limelightDaydream.utils;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.widget.Toast;

import com.google.vr.ndk.base.DaydreamApi;
import com.limelightDaydream.Game;
import com.limelightDaydream.GameDaydream;
import com.limelightDaydream.R;
import com.limelightDaydream.binding.PlatformBinding;
import com.limelightDaydream.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.GfeHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelightDaydream.preferences.PreferenceConfiguration;

import java.io.FileNotFoundException;
import java.net.UnknownHostException;

public class ServerHelper {
    private static DaydreamApi api;

    public static String getCurrentAddressFromComputer(ComputerDetails computer) {
        return computer.reachability == ComputerDetails.Reachability.LOCAL ?
                computer.localAddress : computer.remoteAddress;
    }

    public static Intent createStartIntent(Activity parent, NvApp app, ComputerDetails computer,
                                           ComputerManagerService.ComputerManagerBinder managerBinder) {
        Intent intent = new Intent(parent, Game.class);
        intent = putExtras(intent, app, computer, managerBinder);
        return intent;
    }

    public static Intent putExtras(Intent intent, NvApp app,ComputerDetails computer,
                                   ComputerManagerService.ComputerManagerBinder managerBinder){

        intent.putExtra(Game.EXTRA_HOST, getCurrentAddressFromComputer(computer));
        intent.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        intent.putExtra(Game.EXTRA_APP_ID, app.getAppId());
        intent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        intent.putExtra(Game.EXTRA_UNIQUEID, managerBinder.getUniqueId());
        intent.putExtra(Game.EXTRA_STREAMING_REMOTE,
                computer.reachability != ComputerDetails.Reachability.LOCAL);
        intent.putExtra(Game.EXTRA_PC_UUID, computer.uuid.toString());
        intent.putExtra(Game.EXTRA_PC_NAME, computer.name);
        return intent;
    }

    public static void doStart(Activity parent, NvApp app, ComputerDetails computer,
                               ComputerManagerService.ComputerManagerBinder managerBinder) {


        //createStartIntent(parent, app, computer, managerBinder);

        //if (PreferenceConfiguration.readPreferences(this).enableDaydream) {
        api = DaydreamApi.create(parent);
        if (api  != null) {
            Intent intent = DaydreamApi.createVrIntent(
                    new ComponentName(parent, GameDaydream.class));
            intent.setData(parent.getIntent().getData());
            intent = putExtras(intent,app, computer, managerBinder);
            api.launchInVr(intent);
            api.close();
        }
        //finish();
        //}else {
        //    parent.startActivity(createStartIntent(parent, app, computer, managerBinder));
        //}
    }

    /*public static void doQuit(final Activity parent,
                              final String address,
                              final NvApp app,
                              final ComputerManagerService.ComputerManagerBinder managerBinder,
                              final Runnable onComplete) {
        Toast.makeText(parent, parent.getResources().getString(R.string.applist_quit_app) + " " + app.getAppName() + "...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(address,
                            managerBinder.getUniqueId(), null, PlatformBinding.getCryptoProvider(parent));
                    if (httpConn.quitApp()) {
                        message = parent.getResources().getString(R.string.applist_quit_success) + " " + app.getAppName();
                    } else {
                        message = parent.getResources().getString(R.string.applist_quit_fail) + " " + app.getAppName();
                    }
                } catch (GfeHttpResponseException e) {
                    if (e.getErrorCode() == 599) {
                        message = "This session wasn't started by this device," +
                                " so it cannot be quit. End streaming on the original " +
                                "device or the PC itself. (Error code: "+e.getErrorCode()+")";
                    }
                    else {
                        message = e.getMessage();
                    }
                } catch (UnknownHostException e) {
                    message = parent.getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = parent.getResources().getString(R.string.error_404);
                } catch (Exception e) {
                    message = e.getMessage();
                } finally {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }

                final String toastMessage = message;
                parent.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }*/
}
