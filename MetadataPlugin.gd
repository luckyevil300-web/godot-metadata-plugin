## MetadataPlugin.gd
## Wrapper GDScript para o GodotMetadataPlugin (Android).
## Adicione este script como AutoLoad em Project > Project Settings > AutoLoad.
##
## Uso:
##   var meta = MetadataPlugin.ler("/sdcard/Music/track.mp3")
##   MetadataPlugin.escanear_biblioteca(30000)

extends Node

const PLUGIN_NAME := "GodotMetadataPlugin"

var _plugin: Object = null
var _disponivel := false

func _ready() -> void:
	if Engine.has_singleton(PLUGIN_NAME):
		_plugin = Engine.get_singleton(PLUGIN_NAME)
		_disponivel = true
		print("[MetadataPlugin] Plugin carregado com sucesso.")
	else:
		push_warning("[MetadataPlugin] Plugin não encontrado. Rodando fora do Android ou plugin não instalado.")


## Lê os metadados de um arquivo de áudio.
## Retorna um Dictionary com os campos, ou {"error": "..."} em caso de falha.
##
## Campos retornados:
##   title, artist, album_artist, album, year, genre,
##   track_number, disc_number, composer, bpm, isrc, encoder, comment, lyrics,
##   duration_seconds (int), bit_rate_kbps (int), sample_rate_hz (int), channels (int),
##   format (String), has_artwork (bool),
##   artwork_bytes (PackedByteArray) — somente se has_artwork = true,
##   artwork_mime (String)
func ler(caminho: String) -> Dictionary:
	if not _disponivel:
		return {"error": "Plugin não disponível nesta plataforma."}
	return _plugin.readMetadata(caminho)


## Verifica se o arquivo tem formato suportado.
func suporta(caminho: String) -> bool:
	if not _disponivel:
		return false
	return _plugin.supportsFile(caminho)


## Escaneia a biblioteca de músicas via MediaStore.
## Retorna um Array de Dictionaries (mesmos campos que ler(), mais content_uri e album_id).
##
## Parâmetros:
##   duracao_minima_ms: duração mínima em ms (ex: 30000 = 30s). Use 0 para sem filtro.
##   somente_musica:    se true, exclui ringtones/notificações/alarmes.
func escanear_biblioteca(duracao_minima_ms: int = 30000, somente_musica: bool = true) -> Array:
	if not _disponivel:
		return []
	return Array(_plugin.scanMediaStore(duracao_minima_ms, somente_musica))


## Extrai a capa do álbum como Texture2D pronta para exibir numa TextureRect.
## Retorna null se não houver artwork.
func capa_como_texture(caminho: String) -> ImageTexture:
	var meta := ler(caminho)
	if meta.get("error", "") != "" or not meta.get("has_artwork", false):
		return null

	var img := Image.new()
	var err: int

	var mime: String = meta.get("artwork_mime", "image/jpeg")
	var bytes: PackedByteArray = meta["artwork_bytes"]

	if mime == "image/png":
		err = img.load_png_from_buffer(bytes)
	else:
		err = img.load_jpg_from_buffer(bytes)

	if err != OK:
		push_error("[MetadataPlugin] Erro ao decodificar artwork: %d" % err)
		return null

	return ImageTexture.create_from_image(img)


## Extrai a capa para um arquivo em disco e retorna o caminho.
## Útil para cachear capas entre sessões.
## destino: caminho completo de saída (ex: user://covers/minha_capa.jpg)
func salvar_capa(caminho_audio: String, destino: String) -> bool:
	if not _disponivel:
		return false
	# Converte caminho user:// para caminho absoluto do sistema
	var caminho_abs := ProjectSettings.globalize_path(destino)
	return _plugin.extractArtworkToFile(caminho_audio, caminho_abs)


## Retorna true se o plugin está disponível (rodando no Android com plugin instalado).
func disponivel() -> bool:
	return _disponivel
