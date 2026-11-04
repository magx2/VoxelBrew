package dev.voxelbrew.gui;

import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.engine.export.ExporterRegistry;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.util.Duration;
import java.nio.file.Path;

public final class WorkbenchApplication extends Application {
    private Workbench workbench;
    @Override public void start(Stage stage) throws java.io.IOException {
        if(!Platform.isSupported(ConditionalFeature.SCENE3D)) throw new IllegalStateException("JavaFX 3D is unavailable. Check graphics drivers and WSLg/Mesa. For Mesa software rendering use JAVA_OPTS='-Dprism.order=es2 -Dprism.forceGPU=true'.");
        var args=getParameters().getRaw();Path plugins=ExporterRegistry.defaultDirectory(),open=null;
        for(int i=0;i<args.size();i++) {
            if(args.get(i).equals("--plugin-dir")&&i+1<args.size())plugins=Path.of(args.get(++i));
            else if(args.get(i).equals("--open")&&i+1<args.size())open=Path.of(args.get(++i));
            else if(!args.get(i).equals("--smoke-test"))throw new IllegalArgumentException("Usage: gui [--open design.jcad] [--plugin-dir directory] [--smoke-test]");
        }
        workbench=new Workbench(stage,plugins);var screen=Screen.getPrimary().getVisualBounds();
        stage.setScene(new Scene(workbench,Math.min(1400,screen.getWidth()-60),Math.min(900,screen.getHeight()-80)));stage.setMinWidth(1100);stage.setMinHeight(700);stage.show();
        if(open!=null)workbench.controller().open(open).exceptionally(failure->null);
        if(args.contains("--smoke-test")) {
            var controller=workbench.controller();
            controller.add(new Feature.Sketch("plate","plate.profile",new Feature.Plane("origin-xy",0),new Feature.Rectangle(0,0,60,40)));
            controller.add(new Feature.Extrude("thickness",new Feature.ProfileRef("plate","plate.profile"),8));
            controller.add(new Feature.Sketch("hole","hole.profile",new Feature.Plane("origin-xy",0),new Feature.Circle(10,10,4)));
            controller.add(new Feature.ThroughCut("cut",new Feature.ProfileRef("hole","hole.profile"),new Feature.BodyRef("thickness","body")));
            var pause=new PauseTransition(Duration.seconds(5));pause.setOnFinished(e->{
                try {
                    if(controller.rebuilding()||!controller.canExport())throw new IllegalStateException("Smoke rebuild did not finish: "+controller.error());
                    Screenshot.save(stage.getScene(),Path.of(System.getProperty("voxelbrew.smoke.output","build/gui-smoke.png")));
                    System.out.println("GUI_SMOKE_PASS JavaFX 3D rendered native reference part");workbench.close();stage.hide();Platform.exit();
                }catch(Exception failure){failure.printStackTrace();System.exit(1);}
            });pause.play();
        }
    }
    @Override public void stop(){if(workbench!=null)workbench.close();}
}
