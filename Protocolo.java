/**
 * Protocolo.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Centraliza todas las constantes y el formato del protocolo del chat.
 *
 * PROTOCOLO TCP (DataInputStream/DataOutputStream con writeUTF/readUTF):
 *   JOIN|<usuario>
 *   LEAVE|<usuario>
 *   MSG|<usuario>|<texto>
 *   FILE|<usuario>|<nombreArchivo>|<tamanioEnBytes>   ← luego vienen los bytes crudos
 *   HIST_INI                                          ← señal: empieza el historial
 *   HIST_FIN                                          ← señal: terminó el historial
 *
 * PROTOCOLO UDP (descubrimiento en red local):
 *   Cliente envía:    DISCOVER
 *   Servidor responde: SINFO|<nombre>|<puertoTCP>
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class Protocolo {

    // Puerto TCP donde el servidor acepta conexiones de chat.
    public static final int PUERTO = 5050;

    // Puerto UDP para descubrimiento en la red local.
    // El servidor escucha aquí; el cliente envía un broadcast a este puerto.
    public static final int PUERTO_UDP = 5051;

    // ── Tipos de mensaje TCP ────────────────────────────────────────────────
    public static final String JOIN     = "JOIN";
    public static final String LEAVE    = "LEAVE";
    public static final String MSG      = "MSG";
    public static final String FILE     = "FILE";
    /** Marca el inicio del historial enviado al cliente recién conectado. */
    public static final String HIST_INI = "HIST_INI";
    /** Marca el fin del historial; a partir de aquí todo es mensajes en vivo. */
    public static final String HIST_FIN = "HIST_FIN";

    // ── Tipos de mensaje UDP ────────────────────────────────────────────────
    /** El cliente envía esto (broadcast) para preguntar qué servidores hay. */
    public static final String DESCUBRIR    = "DISCOVER";
    /** El servidor responde con esto a cada solicitud de descubrimiento. */
    public static final String INFO_SERVIDOR = "SINFO";

    // ── Constructores de líneas TCP ─────────────────────────────────────────

    public static String construirJoin(String usuario) {
        return JOIN + "|" + usuario;
    }

    public static String construirLeave(String usuario) {
        return LEAVE + "|" + usuario;
    }

    public static String construirMsg(String usuario, String texto) {
        return MSG + "|" + usuario + "|" + texto;
    }

    /**
     * El tamaño en bytes va en el encabezado para que el receptor sepa
     * exactamente cuántos bytes leer del stream después de esta línea.
     */
    public static String construirFile(String usuario, String nombre, long tamanio) {
        return FILE + "|" + usuario + "|" + nombre + "|" + tamanio;
    }

    // ── Constructores de mensajes UDP ───────────────────────────────────────

    /**
     * Respuesta del servidor a un DISCOVER.
     * Ej: "SINFO|MiServidor|5050"
     */
    public static String construirInfoServidor(String nombre, int puertoTCP) {
        return INFO_SERVIDOR + "|" + nombre + "|" + puertoTCP;
    }

    // ── Parseo ──────────────────────────────────────────────────────────────

    /**
     * Divide una línea de protocolo en sus campos.
     * Límite -1 para conservar campos vacíos al final.
     */
    public static String[] parsear(String linea) {
        return linea.split("\\|", -1);
    }

    /** Extrae solo el tipo (primer campo) sin parsear todo el mensaje. */
    public static String obtenerTipo(String linea) {
        int idx = linea.indexOf('|');
        return idx == -1 ? linea : linea.substring(0, idx);
    }
}
