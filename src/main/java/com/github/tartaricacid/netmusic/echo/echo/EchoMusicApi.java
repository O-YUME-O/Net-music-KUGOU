package com.github.tartaricacid.netmusic.echo.echo;

import java.util.ArrayList;
import java.util.List;

public class EchoMusicApi {
    public static class Song {
        public String id;
        public String name;
        public String singer;
        public String album;
        public String hash;
        public String albumId;
        public int duration;

        public Song(String id, String name, String singer, String album, String hash, String albumId, int duration) {
            this.id = id;
            this.name = name;
            this.singer = singer;
            this.album = album;
            this.hash = hash;
            this.albumId = albumId;
            this.duration = duration;
        }
    }
}
