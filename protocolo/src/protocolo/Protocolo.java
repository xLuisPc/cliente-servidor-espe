package protocolo;

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
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class Protocolo {

    // Puerto TCP donde el servidor acepta conexiones de chat.
    public static final int PUERTO = 5050;

    // ── Tipos de mensaje TCP ────────────────────────────────────────────────
    public static final String JOIN     = "JOIN";
    public static final String LEAVE    = "LEAVE";
    public static final String MSG      = "MSG";
    public static final String FILE     = "FILE";

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
