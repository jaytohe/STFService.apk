/*
 *  Copyright (C) 2019 Orange
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package jp.co.cyberagent.stf;

import android.graphics.Point;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import jp.co.cyberagent.stf.compat.InputManagerWrapper;
import jp.co.cyberagent.stf.compat.WindowManagerWrapper;
import jp.co.cyberagent.stf.util.InternalApi;

@RequiresApi(api = Build.VERSION_CODES.ICE_CREAM_SANDWICH)
public class MinitouchAgent extends Thread {
    private static final String TAG = MinitouchAgent.class.getSimpleName();
    private static final String SOCKET = "minitouchagent";
    private static final int DEFAULT_MAX_CONTACTS = 10;
    private static final int DEFAULT_MAX_PRESSURE = 0;
    private final int width;
    private final int height;
    private int activePointers = -1;
    private LocalServerSocket serverSocket;

    private final HashMap<Integer, PointerContact> PointerContacts;
    private final InputManagerWrapper inputManager;
    private final WindowManagerWrapper windowManager;
    private final Handler handler;

    public MinitouchAgent(int width, int height, Handler handler) {
        this.width = width;
        this.height = height;
        this.handler = handler;
        inputManager = new InputManagerWrapper();
        windowManager = new WindowManagerWrapper();
        PointerContacts = new HashMap<Integer, PointerContact>();
   }

    /*
     * PointerContact represents a input contact to the screen.
     * A contact can be down, up or moving.
     * The state of it is checked with the corresponding isDown, isCommited, markedFor Removal.
     *
     */
    private class PointerContact {
       MotionEvent.PointerCoords current;
        MotionEvent.PointerProperties properties;
        boolean isCommited = false;
        boolean markedForRemoval = false;
        boolean isDown = false;
        long last_down;

        public PointerContact(int[] v) {
            this.properties = new MotionEvent.PointerProperties();
            this.properties.id = ++activePointers; //default base pointer 1 id = 0
            this.properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
            //this.previous = new MotionEvent.PointerCoords();
            this.current = new MotionEvent.PointerCoords();
            this.last_down = System.currentTimeMillis();
            setPointerCoords(this.current, v);
        }

        public void setPointerCoords(MotionEvent.PointerCoords t, int[] v) {
            t.orientation = 0;
            t.pressure = v[2];
            t.size = 1;
            float[] coords = convertCoordinates(v);
            t.x = coords[0];
            t.y = coords[1];
        }

	/* Find real position on screen for a given v vector where v = {x, y, pressure} */
        private float[] convertCoordinates(int[] v) {
            int rotation = windowManager.getRotation();
            double rad = Math.toRadians(rotation * 90.0);
            return new float[]{
                (float) (v[0] * Math.cos(-rad) - v[1] * Math.sin(-rad)),
                (float) (rotation * width) + (float) (v[0] * Math.sin(-rad) + v[1] * Math.cos(-rad)),
            };
        }
    }


   /**
     * Get the width and height of the display by getting the DisplayInfo through reflection
     * Using the android.hardware.display.DisplayManagerGlobal but there might be other ways.
     *
     * @return a Point whose x is the width and y the height of the screen
     */
    static Point getScreenSize() {
        Object displayManager = InternalApi.getSingleton("android.hardware.display.DisplayManagerGlobal");
        try {
            Object displayInfo = displayManager.getClass().getMethod("getDisplayInfo", int.class)
                .invoke(displayManager, Display.DEFAULT_DISPLAY);
            if (displayInfo != null) {
                Class<?> cls = displayInfo.getClass();
                int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
                int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
                return new Point(width, height);
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
        return null;
    }


    /**
     * Keep a way to start only the MinitouchAgent for debugging purpose
     */
    public static void main(String[] args) {
        //To create a Handler our main thread has to prepare the Looper
        Looper.prepare();
        Handler handler = new Handler();
        Point size = getScreenSize();
        if(size != null) {
            MinitouchAgent m = new MinitouchAgent(size.x, size.y, handler);
            m.start();
            Looper.loop();
        } else {
            System.err.println("Couldn't get screen resolution");
            System.exit(1);
        }
    }

    private void injectEvent(InputEvent event) {
        handler.post(() -> inputManager.injectInputEvent(event));
    }

    /*
     * Procedure that fills in a PointerProperties array based on the pointers that are currently active/onscreen.
     */
    private void getActivePointerProperties(MotionEvent.PointerProperties[] properties) {
        int index = 0;
        for (PointerContact pc: PointerContacts.values()) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index] = pc.properties; //shallow copy properties obj to array.
            System.out.println("["+index+"]: "+"[pid, tooltype]:"+ properties[index].id+", "+properties[index].toolType+"]");
            ++index;
        }
    }
    /*
     * Procedure that fills in a PointerCoords array based on the pointer that are currently on screen.
     */
    private void getActivePointerCoords(MotionEvent.PointerCoords[] coords) {
        int index = 0;
        for (PointerContact pc: PointerContacts.values()) {
            coords[index] = new MotionEvent.PointerCoords();
            coords[index] = pc.current; //shallow copy current obj to array.
            System.out.println("["+index+"]: "+"[x,y] : ["+coords[index].x+ ", "+coords[index].y+"]");
            ++index;
        }
    }

    /*
     * @param pc is a new PointerContact obj for a new pointer to be displayed.
     * @param action is the action = {down, up, move} that pc will perform on screen.
     * @return an injectable MotionEvent for a new pointer action.
     */
    private MotionEvent getMotionEvent(PointerContact pc, int action) {
        long now = SystemClock.uptimeMillis();
        long time = (action == MotionEvent.ACTION_DOWN) ? now : pc.last_down;

        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[PointerContacts.size()];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[PointerContacts.size()];

        //Fill in properties and coords
        System.out.println("Sending motion event with properties, coords:");
        getActivePointerProperties(properties);
        getActivePointerCoords(coords);
        System.out.println("activePointers: " + activePointers + ", action : " + action);
        return MotionEvent.obtain(time,
            now,
            action,
            activePointers + 1,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        );
    }
    private void sendBanner(LocalSocket clientSocket) {
        try{
            OutputStreamWriter out = new OutputStreamWriter(clientSocket.getOutputStream());
            out.write("v 1\n");
            String resolution = String.format(Locale.US, "^ %d %d %d %d%n",
                DEFAULT_MAX_CONTACTS, width, height, DEFAULT_MAX_PRESSURE);
            out.write(resolution);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Manages the client connection. The client is supposed to be minitouch.
     */
    private void manageClientConnection() {
        while (true) {
            Log.i(TAG, String.format("Listening on %s", SOCKET));
            LocalSocket clientSocket;
            try {
                clientSocket = serverSocket.accept();
                Log.d(TAG, "client connected");
                sendBanner(clientSocket);
                processCommandLoop(clientSocket);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * processCommandLoop parses touch related commands sent by stf
     * and inject them in Android InputManager.
     * Commmands can be of type down, up, move, commit
     * Note that it currently doesn't support multitouch
     *
     * @param clientSocket the socket to read on
     */
    private void processCommandLoop(LocalSocket clientSocket) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
            String cmd;
            Pattern[] valid_cmd_regex = new Pattern[]{
                Pattern.compile("c"),
                Pattern.compile("w ([0-9]+)"),
                Pattern.compile("u ([0-9]+)"),
                Pattern.compile("d ([0-9]+) ([0-9]+) ([0-9]+) ([0-9]+)"),
                Pattern.compile("m ([0-9]+) ([0-9]+) ([0-9]+) ([0-9]+)"),
            };
            while ((cmd = in.readLine()) != null) {
                for(int k=0; k<valid_cmd_regex.length; ++k) {
                    Matcher m = valid_cmd_regex[k].matcher(cmd);
                    if (m.matches()) {
                        switch(k) {
                            case 0:
                                cmd_commit();
                                break;
                            case 1:
                                cmd_wait(m.group(1));
                                break;
                            case 2:
                                cmd_up(m.group(1));
                                break;

                            case 3:
                                cmd_pos(0, m.group(1), m.group(2), m.group(3), m.group(4));
                                break;

                            case 4:
                                cmd_pos(1, m.group(1), m.group(2), m.group(3), m.group(4));
                                break;
                        }
                        break;
                    }
                }
            }
        }
    }

    /*
     * @return the pointer action id by shifting ACTION_POINTER_INDEX_SHIFT pointer_id times.
     */
    private int calcActionPointer(int action, int pointer_id) {
        return action+(pointer_id << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    /*
     * Runs when the user presses the c key. For more details see minitouch protocol.
     */
    private void cmd_commit() {
        System.out.println("Called commit.");
        //int pointer_id = Integer.parseInt(pid);
        Iterator<Entry<Integer, PointerContact>> entryIt = PointerContacts.entrySet().iterator();
        while (entryIt.hasNext()) {
            //if (PointerContacts.containsKey(pointer_id)) { //means there's at least a `d` action
            //    PointerContact pc = PointerContacts.get(pointer_id);
            Entry<Integer, PointerContact>  entry = entryIt.next();
            PointerContact pc = entry.getValue();
                if (pc.markedForRemoval) {
                    //send UP event here
                    System.out.println("Injecting up");
                    if (pc.properties.id == 0) {
                        injectEvent(getMotionEvent(pc, MotionEvent.ACTION_UP));
                    } else {
                        injectEvent(getMotionEvent(pc, calcActionPointer(MotionEvent.ACTION_POINTER_UP, pc.properties.id)));
                    }
                    entryIt.remove(); //safely remove item to prevent ConcurrentModifationException
                    if (activePointers > -1)
                        activePointers--;
                } else if (!pc.isCommited) {
                    if (pc.isDown) {
                        //send MOVE event here
                        System.out.println("Injecting move");
                        injectEvent(getMotionEvent(pc, MotionEvent.ACTION_MOVE));
                    } else {
                        //send DOWN event here
                       System.out.println("Injecting down with pid: "+pc.properties.id);

                        if (pc.properties.id == 0) {
                            injectEvent(getMotionEvent(pc, MotionEvent.ACTION_DOWN));
                        } else {
                            injectEvent(getMotionEvent(pc, calcActionPointer(MotionEvent.ACTION_POINTER_DOWN, pc.properties.id)));
                        }
                        pc.isDown = true;
                    }
                    pc.isCommited = true;
                }
           // }
        }
        cmd_print();
    }

    /*
     *Debug method that prints out the contents of the PointerContacts hashmap.
     *
     */
    private void cmd_print() {
        Iterator<Entry<Integer, PointerContact>> entryIt = PointerContacts.entrySet().iterator();
        while(entryIt.hasNext()) {
            Entry<Integer, PointerContact> entry = entryIt.next();
            PointerContact q = entry.getValue();
            System.out.println(entry.getKey()+" ["+q.current.x+","+q.current.y+"]");
        }

    }

    /*
     *Sleeps thread <num> ms.
     *
     */
    private void cmd_wait(String dt) {
        System.out.println("Called wait.");
        long t = Long.parseLong(dt);
        try {
            Thread.sleep(t);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /*
     *Runs when user issues `d` (down) command or `m` command (move).
     *
     */
    private void cmd_pos(int mode, String pid, String x, String y, String prs) {
        System.out.println("Called pos.");
        int pointer_id = Integer.parseInt(pid);
        int pointer_x = Integer.parseInt(x);
        int pointer_y = Integer.parseInt(y);
        int pointer_prs = Integer.parseInt(prs);
        if (mode == 0) { //down
            if (!PointerContacts.containsKey(pointer_id)) {
                PointerContacts.put(pointer_id, new PointerContact(new int[]{pointer_x, pointer_y, pointer_prs}));
            }
        }
        else if (mode == 1) { //move
            if (PointerContacts.containsKey(pointer_id)) { // means that contact is commitable
                PointerContact pc = PointerContacts.get(pointer_id);
                if (pc.isCommited && !pc.markedForRemoval) {
                    pc.setPointerCoords(pc.current, new int[]{pointer_x, pointer_y, pointer_prs});
                    pc.isCommited = false; //flip commited to abide to minitouch protocol.
                    PointerContacts.put(pointer_id, pc);
                }
            }
        }
        cmd_print();
    }

    private void cmd_up (String pid) {
        System.out.println("Called up.");
        int pointer_id = Integer.parseInt(pid);

        if (!PointerContacts.containsKey(pointer_id)) {
            System.out.printf("Pointer #%d does not exist!\n", pointer_id);
        }
        else {
            PointerContact pc = PointerContacts.get(pointer_id);
            if (pc.isCommited && !pc.markedForRemoval) {
                pc.markedForRemoval = true;
                pc.isCommited = false;
            }
        }
        cmd_print();
    }


    @Override
    public void run() {
        try {
            Log.i(TAG, String.format("creating socket %s", SOCKET));
            serverSocket = new LocalServerSocket(SOCKET);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        manageClientConnection();
        try {
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
