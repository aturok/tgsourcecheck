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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TgMessageListener class for TDLib usage from Java.
 */
public final class ChatFilter {
    private static Client client = null;

    private static final ConcurrentMap<Long, TdApi.Chat> chats = new ConcurrentHashMap<Long, TdApi.Chat>();
    private static final ConcurrentMap<Long, TdApi.Supergroup> supergroups = new ConcurrentHashMap<Long, TdApi.Supergroup>();

    private static HashSet<String> desiredTitles = null;
    private static final HashSet<Long> desiredIds = new HashSet<Long>();

    private static final String newLine = System.getProperty("line.separator");

    public static void setUp(Client clientArg, List<String> chatTitles) {
        client = clientArg;
        desiredTitles = new HashSet<String>(chatTitles);

        System.out.println(desiredTitles);
        getChatList(1000);
    }

    public static boolean isDesiredChatId(long chatid) {
        return desiredIds.contains(chatid);
    }

    private static long minChatOrder() {
        long min = Long.MAX_VALUE;
        for(Map.Entry<Long,TdApi.Chat> chat : chats.entrySet()) {
            long order = chat.getValue().order;
            if(order < min) {
                min = order;
            }
        }
        return min;
    }

    private static long minChatIdForOrder(long order) {
        if(order == Long.MAX_VALUE)
            return 0;
        else {
            HashSet<Long> relevant = new HashSet<Long>();
            for(Map.Entry<Long,TdApi.Chat> chat : chats.entrySet()) {
                if(chat.getValue().order == order) {
                    relevant.add(chat.getKey());
                }
            }
            long min = Long.MAX_VALUE;
            for(long k : relevant) {
                if(k < min) {
                    min = k;
                }
            }
            return min;
        }
    }

    private static void getChatList(final int limit) {
        synchronized (chats) {
            long offsetOrder = minChatOrder();
                long offsetChatId = minChatIdForOrder(offsetOrder);
                client.send(new TdApi.GetChats(offsetOrder, offsetChatId, limit), new Client.ResultHandler() {
                    @Override
                    public void onResult(TdApi.Object object) {
                        switch (object.getConstructor()) {
                            case TdApi.Error.CONSTRUCTOR:
                                System.err.println("Receive an error for GetChats:" + newLine + object);
                                break;
                            case TdApi.Chats.CONSTRUCTOR:
                                System.err.println("Received:" + newLine + object);
                                long[] chatIds = ((TdApi.Chats)object).chatIds;
                                System.err.println(chatIds.length);
                                for(long chatid : chatIds) {
                                    chats.put(chatid, new TdApi.Chat());
                                }

                                if(chatIds.length > 0) {
                                    getChatList(limit);
                                }
                                else {
                                    System.out.println("Total chats found: " + chats.size());
                                    populateChatList();
                                }
                                break;
                            default:
                                System.err.println("Receive wrong response from TDLib:" + newLine + object);
                        }
                    }
                });
        }
    }

    private static void populateChatList() {
        synchronized(chats) {
            System.out.println("Will get: " + chats.keySet().size());
            for (long key : chats.keySet()) {
                client.send(new TdApi.GetChat(key), new Client.ResultHandler() {
                    @Override
                    public void onResult(TdApi.Object object) {
                        switch (object.getConstructor()) {
                            case TdApi.Chat.CONSTRUCTOR:
                                TdApi.Chat chat = ((TdApi.Chat)object);
                                chats.put(key, chat);
                                if(chat.type.getConstructor() == TdApi.ChatTypeSupergroup.CONSTRUCTOR) {
                                    getSupergroupInfo(key, ((TdApi.ChatTypeSupergroup)chat.type).supergroupId);
                                }
                                includeInDesiredIfFits(key);
                                System.out.println("Got " + chat.title);
                                break;
                            default:
                                System.err.println("Received unexpected response from TDLib:" + newLine + object);
                        }
                    }
                });
            }
        }
    }

    private static void getSupergroupInfo(long chatid, int sgid) {
        System.out.println("Will get supergroup info " + chatid);

        client.send(new TdApi.GetSupergroup(sgid), new Client.ResultHandler() {
                    @Override
                    public void onResult(TdApi.Object object) {
                        switch (object.getConstructor()) {
                            case TdApi.Supergroup.CONSTRUCTOR:
                                TdApi.Supergroup supergroup = ((TdApi.Supergroup)object);
                                supergroups.put(chatid, supergroup);
                                includeInDesiredIfFits(chatid);
                                System.out.println("Got Supergroup " + supergroup.username);
                                break;
                            default:
                                System.err.println("Received unexpected response from TDLib:" + newLine + object);
                        }
                    }
                });
    }

    private static void includeInDesiredIfFits(long chatid) {
        if(desiredIds.contains(chatid))
            return;

        TdApi.Chat chat = chats.get(chatid);
        if(chat != null && desiredTitles.contains(chat.title)) {
            desiredIds.add(chatid);
            System.out.println("\tincluded " + chat.title);
        }

        TdApi.Supergroup sg = supergroups.get(chatid); 
        if(sg != null && desiredTitles.contains(sg.username)) {
            desiredIds.add(chatid);
            System.out.println("\tincluded " + sg.username);
        }
    }
}