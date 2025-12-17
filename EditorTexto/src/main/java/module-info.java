module org.example.editortexto {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires java.sql;
    requires vosk;
    requires java.desktop;


    opens org.example.editortexto to javafx.fxml;
    opens org.example.editortexto.nui to javafx.fxml;
    exports org.example.editortexto;
}