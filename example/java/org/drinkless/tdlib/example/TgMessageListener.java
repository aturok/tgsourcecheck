//
// Author: Alexander Turok <chaot8@gmail.com>
//
package org.drinkless.tdlib.example;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.Log;
import org.drinkless.tdlib.TdApi;

import java.io.IOError;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.NavigableSet;
import java.util.Arrays;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.drinkless.tdlib.example.ChatFilter;

/**
 * TgMessageListener class for TDLib usage from Java.
 */
public final class TgMessageListener {
    private static Client client = null;
    private static ChatFilter chatFilter = null;

    private static TdApi.AuthorizationState authorizationState = null;
    private static volatile boolean haveAuthorization = false;
    private static volatile boolean quiting = false;

    private static final Client.ResultHandler defaultHandler = new DefaultHandler();

    private static final Lock authorizationLock = new ReentrantLock();
    private static final Condition gotAuthorization = authorizationLock.newCondition();

    private static final String newLine = System.getProperty("line.separator");
    private static final String commandsLine = "Enter command (gcs - GetChats, lo - LogOut, q - Quit): ";
    private static volatile String currentPrompt = null;

    static {
        System.loadLibrary("tdjni");
    }

    private static void print(String str) {
        if (currentPrompt != null) {
            System.out.println("");
        }
        System.out.println(str);
        if (currentPrompt != null) {
            System.out.print(currentPrompt);
        }
    }

    private static void onAuthorizationStateUpdated(TdApi.AuthorizationState authorizationState) {
        if (authorizationState != null) {
            TgMessageListener.authorizationState = authorizationState;
        }
        switch (TgMessageListener.authorizationState.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                TdApi.TdlibParameters parameters = new TdApi.TdlibParameters();
                parameters.databaseDirectory = "tdlib";
                parameters.useMessageDatabase = true;
                parameters.useSecretChats = true;
                parameters.apiId = 94575;
                parameters.apiHash = "a3406de8d171bb422bb6ddf3bbd800e2";
                parameters.systemLanguageCode = "en";
                parameters.deviceModel = "Desktop";
                parameters.systemVersion = "Unknown";
                parameters.applicationVersion = "1.0";
                parameters.enableStorageOptimizer = true;

                client.send(new TdApi.SetTdlibParameters(parameters), new AuthorizationRequestHandler());
                break;
            case TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR:
                client.send(new TdApi.CheckDatabaseEncryptionKey(), new AuthorizationRequestHandler());
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR: {
                String phoneNumber = promptString("Please enter phone number: ");
                client.send(new TdApi.SetAuthenticationPhoneNumber(phoneNumber, false, false), new AuthorizationRequestHandler());
                break;
            }
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR: {
                String code = promptString("Please enter authentication code: ");
                client.send(new TdApi.CheckAuthenticationCode(code, "", ""), new AuthorizationRequestHandler());
                break;
            }
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR: {
                String password = promptString("Please enter password: ");
                client.send(new TdApi.CheckAuthenticationPassword(password), new AuthorizationRequestHandler());
                break;
            }
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                haveAuthorization = true;
                authorizationLock.lock();
                try {
                    gotAuthorization.signal();
                } finally {
                    authorizationLock.unlock();
                }
                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                haveAuthorization = false;
                print("Logging out");
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                haveAuthorization = false;
                print("Closing");
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                print("Closed");
                if (!quiting) {
                    client = Client.create(new UpdatesHandler(), null, null); // recreate client after previous has closed
                }
                break;
            default:
                System.err.println("Unsupported authorization state:" + newLine + TgMessageListener.authorizationState);
        }
    }

    private static int toInt(String arg) {
        int result = 0;
        try {
            result = Integer.parseInt(arg);
        } catch (NumberFormatException ignored) {
        }
        return result;
    }

    private static String promptString(String prompt) {
        System.out.print(prompt);
        currentPrompt = prompt;
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String str = "";
        try {
            str = reader.readLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
        currentPrompt = null;
        return str;
    }

    private static void getCommand() {
        String command = promptString(commandsLine);
        String[] commands = command.split(" ", 2);
        try {
            switch (commands[0]) {
                case "gcs": {
                    chatFilter = new ChatFilter();
                    chatFilter.setUp(client, Arrays.asList("Михаил Панков", "Saved Messages", "ToolyCafeTestBot", "AlexTurok"));
                    break;
                }
                case "lo":
                    haveAuthorization = false;
                    client.send(new TdApi.LogOut(), defaultHandler);
                    break;
                case "q":
                    quiting = true;
                    haveAuthorization = false;
                    client.send(new TdApi.Close(), defaultHandler);
                    break;
                default:
                    System.err.println("Unsupported command: " + command);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            print("Not enough arguments");
        }
    }

    public static void main(String[] args) throws InterruptedException {


        // disable TDLib log
        Log.setVerbosityLevel(0);
        if (!Log.setFilePath("tdlib.log")) {
            throw new IOError(new IOException("Write access to the current directory is required"));
        }

        client = Client.create(new UpdatesHandler(), null, null);

        // test Client.execute
        defaultHandler.onResult(Client.execute(new TdApi.GetTextEntities("@telegram /test_command https://telegram.org telegram.me @gif @test")));

        // main loop
        while (!quiting) {
            // await authorization
            authorizationLock.lock();
            try {
                while (!haveAuthorization) {
                    gotAuthorization.await();
                }
            } finally {
                authorizationLock.unlock();
            }

            while (haveAuthorization) {
                getCommand();
            }
        }
    }


    private static class DefaultHandler implements Client.ResultHandler {
        @Override
        public void onResult(TdApi.Object object) {
            print(object.toString());
        }
    }

    private static class UpdatesHandler implements Client.ResultHandler {
        private void handleNewMessage(TdApi.UpdateNewMessage updateInfo) {
            TdApi.Message message = updateInfo.message;

            if(chatFilter != null && chatFilter.isDesiredChatId(message.chatId)) {
                System.out.println("Got DESIRED:");
            }
            else {
                System.out.println("Got UNDESIRED:");   
            }
            System.out.println(message.content.toString());
        }

        @Override
        public void onResult(TdApi.Object object) {
            switch (object.getConstructor()) {
                case TdApi.UpdateNewMessage.CONSTRUCTOR:
                    handleNewMessage(((TdApi.UpdateNewMessage) object));
                    break;
                case TdApi.UpdateAuthorizationState.CONSTRUCTOR:
                    onAuthorizationStateUpdated(((TdApi.UpdateAuthorizationState) object).authorizationState);
                    break;
                default:
                    break;
            }
        }
    }

    private static class AuthorizationRequestHandler implements Client.ResultHandler {
        @Override
        public void onResult(TdApi.Object object) {
            switch (object.getConstructor()) {
                case TdApi.Error.CONSTRUCTOR:
                    System.err.println("Receive an error:" + newLine + object);
                    onAuthorizationStateUpdated(null); // repeat last action
                    break;
                case TdApi.Ok.CONSTRUCTOR:
                    // result is already received through UpdateAuthorizationState, nothing to do
                    break;
                default:
                    System.err.println("Receive wrong response from TDLib:" + newLine + object);
            }
        }
    }
}