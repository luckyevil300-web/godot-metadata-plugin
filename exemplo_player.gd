## exemplo_player.gd
## Exemplo de uso do MetadataPlugin num music player Godot 4.
## Anexe a um Node na sua cena principal.

extends Node

@onready var label_titulo: Label = $UI/LabelTitulo
@onready var label_artista: Label = $UI/LabelArtista
@onready var label_album: Label = $UI/LabelAlbum
@onready var label_info: Label = $UI/LabelInfo
@onready var texture_capa: TextureRect = $UI/TextureCapa
@onready var lista_musicas: ItemList = $UI/ListaMusicas

var _biblioteca: Array[Dictionary] = []

func _ready() -> void:
	if not MetadataPlugin.disponivel():
		label_titulo.text = "Plugin não disponível (teste no Android)"
		return
	_carregar_biblioteca()


# ─── Carregar biblioteca ──────────────────────────────────────────────────────

func _carregar_biblioteca() -> void:
	# Escaneia em thread para não travar a UI
	var thread := Thread.new()
	thread.start(_scan_thread)


func _scan_thread() -> void:
	# Mínimo de 30s, apenas músicas (sem ringtones)
	var faixas: Array = MetadataPlugin.escanear_biblioteca(30_000, true)
	_biblioteca.clear()
	for f in faixas:
		_biblioteca.append(f as Dictionary)
	call_deferred("_popular_lista")


func _popular_lista() -> void:
	lista_musicas.clear()
	for faixa in _biblioteca:
		var nome := "%s — %s" % [
			faixa.get("title", "Sem título"),
			faixa.get("artist", "Artista desconhecido")
		]
		lista_musicas.add_item(nome)
	label_info.text = "%d músicas encontradas" % _biblioteca.size()


# ─── Selecionar faixa ────────────────────────────────────────────────────────

func _on_lista_musicas_item_selected(index: int) -> void:
	var faixa: Dictionary = _biblioteca[index]
	var caminho: String = faixa.get("file_path", "")

	if caminho.is_empty():
		push_error("Caminho do arquivo não disponível.")
		return

	# Leitura completa com tags ID3/Vorbis para detalhes extras
	var meta: Dictionary = MetadataPlugin.ler(caminho)

	if meta.get("error", "") != "":
		label_titulo.text = "Erro: " + meta["error"]
		return

	_atualizar_ui(meta)


func _atualizar_ui(meta: Dictionary) -> void:
	label_titulo.text  = meta.get("title", "Sem título")
	label_artista.text = meta.get("artist", "Artista desconhecido")
	label_album.text   = meta.get("album", "")

	var dur_s: int = meta.get("duration_seconds", 0)
	var info := "%d:%02d  •  %d kbps  •  %d Hz  •  %s" % [
		dur_s / 60, dur_s % 60,
		meta.get("bit_rate_kbps", 0),
		meta.get("sample_rate_hz", 0),
		meta.get("format", "?")
	]
	label_info.text = info

	# Capa do álbum
	if meta.get("has_artwork", false):
		var img := Image.new()
		var bytes: PackedByteArray = meta["artwork_bytes"]
		var mime: String = meta.get("artwork_mime", "image/jpeg")
		var err := img.load_jpg_from_buffer(bytes) if mime != "image/png" \
				else img.load_png_from_buffer(bytes)
		if err == OK:
			texture_capa.texture = ImageTexture.create_from_image(img)
	else:
		texture_capa.texture = null

	# Exibe letra se disponível
	var letra: String = meta.get("lyrics", "")
	if not letra.is_empty():
		print("[Player] Letra disponível (%d chars)" % letra.length())
