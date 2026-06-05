package servidor;

/**
 * ManejadorCliente.java
 * ─────────────────────────────────────────────────────────────────────────────
 * Runnable que gestiona la comunicación con UN cliente.
 * El servidor lanza uno de estos en su propio hilo por cada conexión aceptada.
 *
 * Ciclo de vida:
 *   1. run() inicia los streams y espera el primer mensaje (JOIN).
 *   2. JOIN: agrega cliente a la lista de broadcast.
 *   3. Bucle: leer → parsear → actuar (MSG, FILE, LEAVE).
 *   4. Al desconectarse (excepción en readUTF), limpia y notifica.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import protocolo.Protocolo;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class ManejadorCliente implements Runnable {

    private final Socket socket;
    private final List<ManejadorCliente> clientes;

    private DataInputStream  entrada;
    private DataOutputStream salida;
    private String usuario;

    // Flag para evitar que desconectar() se ejecute más de una vez.
    private volatile boolean desconectado = false;

    public ManejadorCliente(Socket socket, List<ManejadorCliente> clientes) {
        this.socket   = socket;
        this.clientes = clientes;
    }

    // ─── Hilo principal ─────────────────────────────────────────────────────

    @Override
    public void run() {
        try {
            // DataInputStream/DataOutputStream: writeUTF escribe un prefijo de 2 bytes
            // con la longitud, y readUTF los usa para leer exactamente ese bloque.
            entrada = new DataInputStream(socket.getInputStream());
            salida  = new DataOutputStream(socket.getOutputStream());

            // Bucle: bloquea en readUTF() hasta que llega un mensaje, lo procesa, repite.
            while (true) {
                String linea = entrada.readUTF();
                procesarLinea(linea);
            }

        } catch (IOException e) {
            // readUTF() lanza EOFException (subclase de IOException) cuando el cliente
            // cierra la conexión. Es el comportamiento normal de cierre TCP.
            desconectar();
        }
    }

    // ─── Procesamiento del protocolo ────────────────────────────────────────

    private void procesarLinea(String linea) throws IOException {
        // Dividir por "|" para extraer el tipo y los campos.
        String[] partes = Protocolo.parsear(linea);
        String tipo = partes[0];

        switch (tipo) {

            case Protocolo.JOIN:
                usuario = partes[1];
                System.out.println("[JOIN ] " + usuario
                        + " desde " + socket.getInetAddress().getHostAddress());

                clientes.add(this);

                // Notificar a los demás que alguien nuevo llegó.
                broadcast(Protocolo.construirMsg("Servidor",
                        "★ " + usuario + " se unió al chat."), false);
                break;

            case Protocolo.MSG:
                System.out.println("[MSG  ] " + usuario + ": " + partes[2]);
                broadcast(linea, false);
                break;

            case Protocolo.FILE:
                String nombreArchivo = partes[2];
                long   tamanio       = Long.parseLong(partes[3]);
                System.out.printf("[FILE ] %s → '%s' (%,d bytes)%n",
                        usuario, nombreArchivo, tamanio);

                // Leer exactamente 'tamanio' bytes en un bucle.
                // TCP puede fragmentar: read() puede devolver menos bytes de los pedidos,
                // así que acumulamos hasta completar el archivo.
                byte[] datos = new byte[(int) tamanio];
                int totalLeidos = 0;
                while (totalLeidos < tamanio) {
                    int leidos = entrada.read(datos, totalLeidos,
                            (int) (tamanio - totalLeidos));
                    if (leidos == -1) throw new IOException("Stream cerrado leyendo archivo.");
                    totalLeidos += leidos;
                }

                broadcastArchivo(linea, datos);
                break;

            case Protocolo.LEAVE:
                desconectar();
                break;
        }
    }

    // ─── Envío a ESTE cliente ────────────────────────────────────────────────

    /**
     * Envía una línea de texto a este cliente.
     * synchronized: varios hilos de broadcast pueden llamar esto al mismo tiempo.
     * Sin sync, las escrituras concurrentes en DataOutputStream mezclarían los bytes.
     */
    public synchronized void enviarMensaje(String linea) {
        if (desconectado) return;
        try {
            salida.writeUTF(linea);
            salida.flush();
        } catch (IOException e) {
            // No propagar: si este cliente falló, no interrumpir el broadcast a los demás.
        }
    }

    /**
     * Envía el encabezado FILE seguido de los bytes crudos del archivo.
     * synchronized por la misma razón que enviarMensaje: garantiza que encabezado
     * y bytes lleguen juntos sin que otro mensaje se intercale entre ellos.
     */
    public synchronized void enviarArchivo(String encabezado, byte[] datos) {
        if (desconectado) return;
        try {
            salida.writeUTF(encabezado);  // encabezado con longitud prefijada
            salida.flush();
            salida.write(datos, 0, datos.length); // bytes crudos del archivo
            salida.flush();
        } catch (IOException e) {
            // No propagar.
        }
    }

    // ─── Broadcast ──────────────────────────────────────────────────────────

    private void broadcast(String linea, boolean incluirEmisor) {
        // Copia de la lista para no retener el lock de synchronizedList durante el envío.
        // Si iteráramos directamente sobre la lista, otro hilo podría modificarla
        // (add/remove) y lanzar ConcurrentModificationException.
        List<ManejadorCliente> copia;
        synchronized (clientes) {
            copia = new ArrayList<>(clientes);
        }
        for (ManejadorCliente c : copia) {
            if (!incluirEmisor && c == this) continue;
            c.enviarMensaje(linea);
        }
    }

    private void broadcastArchivo(String encabezado, byte[] datos) {
        List<ManejadorCliente> copia;
        synchronized (clientes) {
            copia = new ArrayList<>(clientes);
        }
        for (ManejadorCliente c : copia) {
            if (c == this) continue;
            c.enviarArchivo(encabezado, datos);
        }
    }

    // ─── Desconexión limpia ──────────────────────────────────────────────────

    private void desconectar() {
        if (desconectado) return;
        desconectado = true;

        // Quitar de la lista ANTES del broadcast: así el desconectado
        // no se incluye en su propio mensaje de "salió del chat".
        clientes.remove(this);

        if (usuario != null) {
            System.out.println("[LEAVE] " + usuario + " se desconectó.");
            broadcast(Protocolo.construirMsg("Servidor",
                    "✖ " + usuario + " salió del chat."), true);
        }

        try { socket.close(); } catch (IOException e) { /* ignorar */ }
    }
}
