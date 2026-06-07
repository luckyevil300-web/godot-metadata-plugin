package com.musicplayer.godot;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.UsedByGodot;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.datatype.Artwork;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plugin Godot 4 para leitura de metadados ID3/Vorbis de arquivos de áudio.
 *
 * <h2>Como registrar no Godot 4</h2>
 * <ol>
 *   <li>Coloque {@code GodotMetadataPlugin.aar} em {@code android/plugins/}</li>
 *   <li>Coloque {@code GodotMetadataPlugin.gdap} na mesma pasta</li>
 *   <li>Em Project → Export → Android, habilite o plugin</li>
 * </ol>
 *
 * <h2>Uso em GDScript</h2>
 * <pre>
 * var plugin: Object
 *
 * func _ready() -> void:
 *     if Engine.has_singleton("GodotMetadataPlugin"):
 *         plugin = Engine.get_singleton("GodotMetadataPlugin")
 *     else:
 *         push_error("Plugin de metadados não encontrado!")
 *
 * func ler_musica(caminho: String) -> void:
 *     var meta: Dictionary = plugin.readMetadata(caminho)
 *     if meta.get("error", "") != "":
 *         print("Erro: ", meta["error"])
 *         return
 *     print("Título:  ", meta["title"])
 *     print("Artista: ", meta["artist"])
 *     print("Álbum:   ", meta["album"])
 *     print("Duração: ", meta["duration_seconds"], "s")
 *     if meta["has_artwork"]:
 *         var img = Image.new()
 *         img.load_jpg_from_buffer(meta["artwork_bytes"])
 *
 * func escanear_biblioteca() -> void:
 *     var tracks: Array = plugin.scanMediaStore(30000, true)
 *     for t in tracks:
 *         print(t["title"], " — ", t["artist"])
 * </pre>
 */
public class GodotMetadataPlugin extends GodotPlugin {

    private static final String TAG = "GodotMetadataPlugin";

    static {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.WARNING);
    }

    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(
            "mp3", "ogg", "flac", "m4a", "mp4", "wav", "aif", "aiff"
    ));

    public GodotMetadataPlugin(Godot godot) {
        super(godot);
    }

    @Override
    public String getPluginName() {
        return "GodotMetadataPlugin";
    }

    // =========================================================================
    // API exposta ao GDScript via @UsedByGodot
    // =========================================================================

    /**
     * Lê os metadados de um arquivo de áudio.
     *
     * @param filePath caminho absoluto do arquivo (ex: {@code /sdcard/Music/track.mp3})
     * @return Dictionary com os campos de metadados, ou {@code {"error": "mensagem"}} em caso de falha
     *
     * <p>Campos retornados (todos String exceto indicado):</p>
     * <ul>
     *   <li>{@code title}, {@code artist}, {@code album_artist}, {@code album}</li>
     *   <li>{@code year}, {@code genre}, {@code track_number}, {@code disc_number}</li>
     *   <li>{@code composer}, {@code bpm}, {@code isrc}, {@code encoder}, {@code comment}</li>
     *   <li>{@code lyrics}</li>
     *   <li>{@code duration_seconds} (int), {@code bit_rate_kbps} (int), {@code sample_rate_hz} (int), {@code channels} (int)</li>
     *   <li>{@code format} (String: "MP3", "FLAC", etc.)</li>
     *   <li>{@code has_artwork} (bool)</li>
     *   <li>{@code artwork_bytes} (PackedByteArray) — presente somente se has_artwork = true</li>
     *   <li>{@code artwork_mime} (String)</li>
     *   <li>{@code error} (String) — presente somente em caso de erro</li>
     * </ul>
     */
    @UsedByGodot
    public HashMap<String, Object> readMetadata(String filePath) {
        HashMap<String, Object> result = new HashMap<>();

        if (filePath == null || filePath.isBlank()) {
            result.put("error", "Caminho do arquivo inválido ou vazio.");
            return result;
        }

        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            result.put("error", "Arquivo não encontrado: " + filePath);
            return result;
        }

        String ext = extension(file.getName());
        if (!SUPPORTED.contains(ext)) {
            result.put("error", "Formato não suportado: " + ext);
            return result;
        }

        try {
            AudioFile audioFile = AudioFileIO.read(file);
            AudioHeader header = audioFile.getAudioHeader();
            Tag tag = audioFile.getTag();

            // Campos de áudio técnicos
            result.put("format", ext.toUpperCase());
            result.put("duration_seconds", header != null ? header.getTrackLength() : 0);
            result.put("bit_rate_kbps",   header != null ? parseBitRate(header.getBitRate()) : 0);
            result.put("sample_rate_hz",  header != null ? parseSampleRate(header.getSampleRate()) : 0);
            result.put("channels",        header != null ? parseChannels(header.getChannels()) : 0);

            // Tags de metadados
            if (tag != null) {
                result.put("title",        get(tag, FieldKey.TITLE));
                result.put("artist",       get(tag, FieldKey.ARTIST));
                result.put("album_artist", get(tag, FieldKey.ALBUM_ARTIST));
                result.put("album",        get(tag, FieldKey.ALBUM));
                result.put("year",         get(tag, FieldKey.YEAR));
                result.put("genre",        get(tag, FieldKey.GENRE));
                result.put("track_number", get(tag, FieldKey.TRACK));
                result.put("disc_number",  get(tag, FieldKey.DISC_NO));
                result.put("composer",     get(tag, FieldKey.COMPOSER));
                result.put("bpm",          get(tag, FieldKey.BPM));
                result.put("isrc",         get(tag, FieldKey.ISRC));
                result.put("encoder",      get(tag, FieldKey.ENCODER));
                result.put("comment",      get(tag, FieldKey.COMMENT));
                result.put("lyrics",       get(tag, FieldKey.LYRICS));

                // Artwork (capa do álbum)
                Artwork artwork = tag.getFirstArtwork();
                boolean hasArtwork = artwork != null
                        && artwork.getBinaryData() != null
                        && artwork.getBinaryData().length > 0;
                result.put("has_artwork", hasArtwork);
                if (hasArtwork) {
                    result.put("artwork_bytes", artwork.getBinaryData());
                    result.put("artwork_mime",  artwork.getMimeType() != null
                            ? artwork.getMimeType() : "image/jpeg");
                }
            } else {
                fillEmptyTags(result);
            }

        } catch (Exception e) {
            Log.e(TAG, "Erro ao ler " + filePath, e);
            result.put("error", e.getMessage() != null ? e.getMessage() : "Erro desconhecido");
        }

        return result;
    }

    /**
     * Verifica se o plugin consegue ler o arquivo informado.
     *
     * @param filePath caminho absoluto do arquivo
     * @return true se o formato for suportado e o arquivo existir
     */
    @UsedByGodot
    public boolean supportsFile(String filePath) {
        if (filePath == null) return false;
        File f = new File(filePath);
        return f.exists() && f.isFile() && SUPPORTED.contains(extension(f.getName()));
    }

    /**
     * Escaneia a biblioteca de músicas do Android via MediaStore.
     *
     * @param minDurationMs duração mínima em milissegundos (ex: 30000 para 30s)
     * @param onlyMusic     se true, filtra apenas arquivos marcados como música pelo SO
     * @return Array de Dictionaries; cada item tem os mesmos campos que {@link #readMetadata}
     *         mais {@code content_uri} e {@code album_id}
     */
    @UsedByGodot
    public Object[] scanMediaStore(int minDurationMs, boolean onlyMusic) {
        Context ctx = getActivity();
        if (ctx == null) return new Object[0];

        Uri audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] projection = buildProjection();
        String selection = buildSelection(minDurationMs, onlyMusic);

        java.util.List<HashMap<String, Object>> tracks = new java.util.ArrayList<>();

        try (Cursor cursor = ctx.getContentResolver().query(
                audioUri, projection, selection, null,
                MediaStore.Audio.Media.TITLE + " ASC")) {

            if (cursor == null) return new Object[0];

            while (cursor.moveToNext()) {
                HashMap<String, Object> track = cursorToMap(cursor);
                if (track != null) tracks.add(track);
            }

        } catch (Exception e) {
            Log.e(TAG, "Erro ao escanear MediaStore", e);
        }

        return tracks.toArray();
    }

    /**
     * Extrai a capa de um arquivo e salva em disco como JPEG.
     *
     * @param audioFilePath caminho do arquivo de áudio
     * @param outputPath    caminho de destino da imagem (ex: {@code /data/user/0/com.meu.jogo/files/cover.jpg})
     * @return true se a capa foi salva com sucesso
     */
    @UsedByGodot
    public boolean extractArtworkToFile(String audioFilePath, String outputPath) {
        try {
            AudioFile audioFile = AudioFileIO.read(new File(audioFilePath));
            Tag tag = audioFile.getTag();
            if (tag == null) return false;
            Artwork artwork = tag.getFirstArtwork();
            if (artwork == null || artwork.getBinaryData() == null) return false;

            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                fos.write(artwork.getBinaryData());
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Erro ao extrair artwork", e);
            return false;
        }
    }

    // =========================================================================
    // Internos
    // =========================================================================

    private String get(Tag tag, FieldKey key) {
        try {
            String v = tag.getFirst(key);
            return (v != null && !v.isBlank()) ? v.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void fillEmptyTags(HashMap<String, Object> result) {
        for (String k : new String[]{
                "title", "artist", "album_artist", "album", "year", "genre",
                "track_number", "disc_number", "composer", "bpm", "isrc",
                "encoder", "comment", "lyrics"}) {
            result.put(k, "");
        }
        result.put("has_artwork", false);
    }

    private int parseBitRate(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    private int parseSampleRate(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    private int parseChannels(String s) {
        if (s == null) return 0;
        if (s.toLowerCase().contains("mono"))   return 1;
        if (s.toLowerCase().contains("stereo")) return 2;
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private String[] buildProjection() {
        java.util.List<String> cols = new java.util.ArrayList<>(java.util.Arrays.asList(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATA
        ));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cols.add(MediaStore.Audio.Media.ALBUM_ARTIST);
            cols.add(MediaStore.Audio.Media.GENRE);
            cols.add(MediaStore.Audio.Media.BITRATE);
        }
        return cols.toArray(new String[0]);
    }

    private String buildSelection(int minDurationMs, boolean onlyMusic) {
        java.util.List<String> clauses = new java.util.ArrayList<>();
        if (onlyMusic) clauses.add(MediaStore.Audio.Media.IS_MUSIC + " != 0");
        if (minDurationMs > 0)
            clauses.add(MediaStore.Audio.Media.DURATION + " >= " + minDurationMs);
        return clauses.isEmpty() ? null : String.join(" AND ", clauses);
    }

    private HashMap<String, Object> cursorToMap(Cursor cursor) {
        try {
            HashMap<String, Object> m = new HashMap<>();
            long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);

            m.put("content_uri",      uri.toString());
            m.put("album_id",         id);
            m.put("title",            getString(cursor, MediaStore.Audio.Media.TITLE));
            m.put("artist",           getString(cursor, MediaStore.Audio.Media.ARTIST));
            m.put("album",            getString(cursor, MediaStore.Audio.Media.ALBUM));
            m.put("year",             getString(cursor, MediaStore.Audio.Media.YEAR));
            m.put("track_number",     getString(cursor, MediaStore.Audio.Media.TRACK));
            m.put("mime_type",        getString(cursor, MediaStore.Audio.Media.MIME_TYPE));
            m.put("file_path",        getString(cursor, MediaStore.Audio.Media.DATA));
            m.put("duration_seconds", cursor.getLong(cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.DURATION)) / 1000);
            m.put("file_size_bytes",  cursor.getLong(cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.SIZE)));
            m.put("date_added",       cursor.getLong(cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.DATE_ADDED)));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                m.put("album_artist", getString(cursor, MediaStore.Audio.Media.ALBUM_ARTIST));
                m.put("genre",        getString(cursor, MediaStore.Audio.Media.GENRE));
                int brIdx = cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE);
                m.put("bit_rate_kbps", brIdx >= 0 ? cursor.getInt(brIdx) / 1000 : 0);
            }

            return m;
        } catch (Exception e) {
            Log.w(TAG, "Erro ao mapear cursor: " + e.getMessage());
            return null;
        }
    }

    private String getString(Cursor cursor, String column) {
        int idx = cursor.getColumnIndex(column);
        if (idx < 0) return "";
        String v = cursor.getString(idx);
        return v != null ? v : "";
    }
}
