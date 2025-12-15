# Mi Editor de Texto 

README de mi editor de texto hecho con JavaFX, al que le he ido metiendo cosas nuevas como una progressLabel y botones de importar y exportar, al igual que un textflow que señaliza los cambios en el texto.

## Cosas Nuevas

Últimamente he añadido un par de funcionalidades para que sea más útil:

*   **Botones de Abrir y Exportar** 
    *   Ahora la aplicación tiene botones para `Abrir` un archivo de texto (funciona  con `.md`(Formato readme) aunque no he conseguido que guarde correctamente el formato) y para `Exportar` el contenido que haya en el visor.

*   **Componente ProgressLabel** 
    *   He creado un componente visual nuevo para que se muestre una barra de progreso con texto.
    *   Aparece cuando haces alguna acción que puede tardar un poco (el % funciona guay, pero la barra , aunque creo que si funciona bien, al no tener más espacio en el editor on se ve, tendría que cambiar todas las hbox y vbox para que la altura de la progressLabel fuese la suficiente para que se viese y eso tardaria muchisimo tiempo), como guardar, para que sepas que el programa está trabajando en ello. La clase que lo controla está en `ProgressLabel.java`.

Y eso es todo por ahora. 😸😸😸
