package org.example.editortexto.nui;

import java.util.ArrayList;
import java.util.List;

public class NuiController {
    private static final List<NuiListener> listeners = new ArrayList();

    public void añadirNuiListener(NuiListener listener) {
        if (!this.listeners.contains(listener) && listener != null) {
            this.listeners.add(listener);
        }
    }

    public static void procesarComando (NuiCommand comando, String texto) {
        System.out.println("Comando: " + comando);
        for (NuiListener listener : listeners) {
        listener.activarComando(comando, texto);
        }
    }


}
