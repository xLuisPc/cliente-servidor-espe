/**
 * Historial.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Almacena en MEMORIA los mensajes y archivos del chat para poder enviarlos
 * a los clientes que se unan después de que ocurrieron.
 *
 * Thread-safety: todos sus métodos son synchronized. Varios hilos
 * ManejadorCliente pueden llamarlos concurrentemente sin corromper el estado.
 *
 * No se usa ninguna base de datos: los datos viven mientras el servidor esté
 * corriendo. Al reiniciar el servidor, el historial se pierde.
 * ─────────────────────────────────────────────────────────────────────────────
 */
import java.util.ArrayList;
import java.util.List;

public class Historial {

    // Cantidad máxima de entradas. Si se supera, se elimina la más antigua.
    // Esto evita que el servidor consuma memoria ilimitada con muchos archivos.
    private static final int MAX_ENTRADAS = 200;

    // Lista de entradas en orden cronológico.
    // Cada entrada es un Object[]:
    //   { String lineaMsg }               → mensaje de texto (MSG|usuario|texto)
    //   { String lineaFile, byte[] datos } → archivo (encabezado FILE + bytes)
    private final List<Object[]> entradas = new ArrayList<>();

    // ── Agregar al historial ─────────────────────────────────────────────────

    /** Registra un mensaje de texto en el historial. */
    public synchronized void agregarMensaje(String lineaMsg) {
        entradas.add(new Object[]{ lineaMsg });
        podar();
    }

    /** Registra un archivo (encabezado + bytes) en el historial. */
    public synchronized void agregarArchivo(String lineaFile, byte[] datos) {
        entradas.add(new Object[]{ lineaFile, datos });
        podar();
    }

    // ── Reproducir historial ─────────────────────────────────────────────────

    /**
     * Toma una copia instantánea (snapshot) del historial actual y la devuelve.
     * Se usa synchronized para que la copia sea consistente: si otro hilo
     * agrega un mensaje justo en este momento, o lo vemos completo o no lo vemos.
     *
     * Devolvemos una copia para no bloquear el lock mientras enviamos datos
     * por la red (las llamadas a enviarMensaje/enviarArchivo pueden bloquearse).
     */
    private synchronized List<Object[]> snapshot() {
        return new ArrayList<>(entradas);
    }

    /**
     * Envía el historial completo a un cliente recién conectado.
     * Se llama desde el hilo del ManejadorCliente correspondiente.
     *
     * Operación: obtener snapshot (rápido, con lock) → enviar datos (sin lock).
     * Esto evita retener el lock durante I/O de red, que podría bloquear otros hilos.
     */
    public void enviarHistorialA(ManejadorCliente cliente) {
        List<Object[]> copia = snapshot(); // copia rápida con lock
        for (Object[] entrada : copia) {
            if (entrada.length == 1) {
                // Entrada de texto: enviar la línea MSG tal como está en el historial
                cliente.enviarMensaje((String) entrada[0]);
            } else {
                // Entrada de archivo: encabezado FILE + bytes
                cliente.enviarArchivo((String) entrada[0], (byte[]) entrada[1]);
            }
        }
    }

    /** Número de entradas actuales (útil para mostrar en la consola del servidor). */
    public synchronized int size() {
        return entradas.size();
    }

    // ── Privado ──────────────────────────────────────────────────────────────

    /** Elimina la entrada más antigua si se superó el máximo. */
    private void podar() {
        if (entradas.size() > MAX_ENTRADAS) {
            entradas.remove(0);
        }
    }
}
