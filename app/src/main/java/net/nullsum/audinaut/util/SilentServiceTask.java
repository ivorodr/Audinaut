package net.nullsum.audinaut.util;

import android.content.Context;

import net.nullsum.audinaut.service.MusicService;
import net.nullsum.audinaut.service.MusicServiceFactory;

public abstract class SilentServiceTask<T> extends SilentBackgroundTask<T> {
    protected MusicService musicService;

    public SilentServiceTask(Context context) {
        super(context);
    }

    @Override
    protected T doInBackground() throws Throwable {
        musicService = MusicServiceFactory.getMusicService(getContext());
        return doInBackground(musicService);
    }

    protected abstract T doInBackground(MusicService musicService) throws Throwable;
}
