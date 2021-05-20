/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package net.nullsum.audinaut.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import net.nullsum.audinaut.R;
import net.nullsum.audinaut.view.ErrorDialog;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Sindre Mehus
 */
public abstract class BackgroundTask<T> implements ProgressListener {
    static final BlockingQueue<BackgroundTask.Task> queue = new LinkedBlockingQueue<>(10);
    private static final String TAG = BackgroundTask.class.getSimpleName();
    private static final int DEFAULT_CONCURRENCY = 8;
    private static final Collection<Thread> threads = Collections.synchronizedCollection(new ArrayList<Thread>());
    private static Handler handler = null;
    private static AtomicInteger currentlyRunning = new AtomicInteger(0);
    protected Runnable onCompletionListener = null;
    protected OnCancelListener cancelListener;

    static {
        try {
            handler = new Handler(Looper.getMainLooper());
        } catch (Exception e) {
            // Not called from main thread
        }
    }

    final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Context context;
    Task task;

    BackgroundTask(Context context) {
        this.context = context;

        if (threads.size() < DEFAULT_CONCURRENCY) {
            for (int i = threads.size(); i < DEFAULT_CONCURRENCY; i++) {
                Thread thread = new Thread(new TaskRunnable(), String.format("BackgroundTask_%d", i));
                threads.add(thread);
                thread.start();
            }
        } else if(currentlyRunning.get() >= threads.size()) {
            Log.w(TAG, "Emergency add new thread: " + (threads.size() + 1));
            Thread thread = new Thread(new TaskRunnable(), String.format("BackgroundTask_%d", threads.size()));
            threads.add(thread);
            thread.start();
        }
        if (handler == null) {
            try {
                handler = new Handler(Looper.getMainLooper());
            } catch (Exception e) {
                // Not called from main thread
            }
        }
    }

    public static void stopThreads() {
        for(Thread thread: threads) {
            thread.interrupt();
        }
        threads.clear();
        queue.clear();
    }

    private AppCompatActivity getActivity() {
        return (context instanceof AppCompatActivity) ? ((AppCompatActivity) context) : null;
    }

    protected Context getContext() {
        return context;
    }

    Handler getHandler() {
        return handler;
    }

    public abstract void execute();

    protected abstract T doInBackground() throws Throwable;

    protected abstract void done(T result);

    protected void error(Throwable error) {
        Log.w(TAG, "Got exception: " + error, error);
        AppCompatActivity activity = getActivity();
        if (activity != null) {
            new ErrorDialog(activity, getErrorMessage(error), true);
        }
    }

    protected String getErrorMessage(Throwable error) {

        if (error instanceof IOException && !Util.isNetworkConnected(context)) {
            return context.getResources().getString(R.string.background_task_no_network);
        }

        if (error instanceof FileNotFoundException) {
            return context.getResources().getString(R.string.background_task_not_found);
        }

        if (error instanceof IOException) {
            return context.getResources().getString(R.string.background_task_network_error);
        }

        if (error instanceof XmlPullParserException) {
            return context.getResources().getString(R.string.background_task_parse_error);
        }

        String message = error.getMessage();
        if (message != null) {
            return message;
        }
        return error.getClass().getSimpleName();
    }

    public void cancel() {
        if(cancelled.compareAndSet(false, true)) {
            if(isRunning()) {
                if(cancelListener != null) {
                    cancelListener.onCancel();
                } else {
                    task.cancel();
                }
            }

            task = null;
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void setOnCancelListener(OnCancelListener listener) {
        cancelListener = listener;
    }

    public boolean isRunning() {
        return task != null && task.isRunning();
    }

    @Override
    public abstract void updateProgress(final String message);

    @Override
    public void updateProgress(int messageId) {
        updateProgress(context.getResources().getString(messageId));
    }

    public void updateProgress() {
        updateProgress(context.getResources().getString(R.string.settings_testing_connection));
    }

    @Override
    public void updateCache(int changeCode) {

    }

    public void setOnCompletionListener(Runnable onCompletionListener) {
        this.onCompletionListener = onCompletionListener;
    }

    class Task {
        private final AtomicBoolean taskStart = new AtomicBoolean(false);
        private Thread thread;

        private void execute() throws Exception {
            // Don't run if cancelled already
            if (isCancelled()) {
                return;
            }

            try {
                thread = Thread.currentThread();
                taskStart.set(true);

                final T result = doInBackground();
                if (isCancelled()) {
                    taskStart.set(false);
                    return;
                }

                if (handler != null) {
                    handler.post(() -> {
                        if (!isCancelled()) {
                            try {
                                onDone(result);
                            } catch (Throwable t) {
                                if (!isCancelled()) {
                                    try {
                                        onError(t);
                                    } catch (Exception e) {
                                        // Don't care
                                    }
                                }
                            }
                        }

                        taskStart.set(false);
                    });
                } else {
                    taskStart.set(false);
                }
            } catch (InterruptedException interrupt) {
                if (taskStart.get()) {
                    // Don't exit root thread if task cancelled
                    throw interrupt;
                }
            } catch (final Throwable t) {
                if (isCancelled()) {
                    taskStart.set(false);
                    return;
                }

                if (handler != null) {
                    handler.post(() -> {
                        if (!isCancelled()) {
                            try {
                                onError(t);
                            } catch (Exception e) {
                                // Don't care
                            }
                        }

                        taskStart.set(false);
                    });
                } else {
                    taskStart.set(false);
                }
            } finally {
                thread = null;
            }
        }

        public void cancel() {
            if (taskStart.compareAndSet(true, false)) {
                if (thread != null) {
                    thread.interrupt();
                }
            }
        }

        public boolean isCancelled() {
            return Thread.interrupted() || BackgroundTask.this.isCancelled();
        }

        public void onDone(T result) {
            done(result);

            if(onCompletionListener != null) {
                onCompletionListener.run();
            }
        }

        public void onError(Throwable t) {
            error(t);
        }

        public boolean isRunning() {
            return taskStart.get();
        }
    }

    private class TaskRunnable implements Runnable {
        private boolean running = true;

        public TaskRunnable() {

        }

        @Override
        public void run() {
            Looper.prepare();
            final Thread currentThread = Thread.currentThread();
            while (running) {
                try {
                    Task task = queue.take();
                    currentlyRunning.incrementAndGet();
                    task.execute();
                } catch (InterruptedException stop) {
                    Log.e(TAG, "Thread died");
                    running = false;
                    threads.remove(Thread.currentThread());
                } catch (Throwable t) {
                    Log.e(TAG, "Unexpected crash in BackgroundTask thread", t);
                }

                currentlyRunning.decrementAndGet();
            }

            if(threads.contains(currentThread)) {
                threads.remove(currentThread);
            }
        }
    }

    public interface OnCancelListener {
        void onCancel();
    }
}
