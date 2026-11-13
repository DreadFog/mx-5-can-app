package com.mx5.dashboard;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NavigationClientTest {
    private static final String FIXTURE = """
            {"code":"Ok","routes":[{"geometry":{"coordinates":[
              [0,0],[0.001,0],[0.001,0.001],[0.002,0.001],[0.003,0.001]]},
              "legs":[{"steps":[
                {"maneuver":{"type":"depart","location":[0,0]}},
                {"maneuver":{"type":"turn","modifier":"slight left","location":[0.001,0]}},
                {"maneuver":{"type":"roundabout","exit":3,"location":[0.001,0.001]}},
                {"maneuver":{"type":"exit roundabout","modifier":"right","location":[0.002,0.001]}},
                {"maneuver":{"type":"arrive","location":[0.003,0.001]}}
              ]}]}]}
            """;

    @Test public void parsesDrivingStepsAndRoundaboutExit() throws Exception {
        NavigationRoute route = NavigationClient.parseRoute(FIXTURE);
        assertEquals(5, route.points.size());
        assertEquals(3, route.cues.size());
        assertEquals(NavigationRoute.Turn.LIGHT_LEFT, route.cues.get(0).turn);
        assertEquals(NavigationRoute.Turn.ROUNDABOUT, route.cues.get(1).turn);
        assertEquals(3, route.cues.get(1).exit);
        assertEquals(NavigationRoute.Turn.ARRIVE, route.cues.get(2).turn);
        assertEquals(111.2, route.cues.get(0).along, 0.2);
        assertEquals(222.4, route.cues.get(1).along, 0.2);
        route.update(new NavigationRoute.Point(0, 0));
        assertEquals(150, NavigationRoute.roundedDistance(route.remaining()));
    }

    @Test(expected = IOException.class) public void rejectsNoRoute() throws Exception {
        NavigationClient.parseRoute("{\"code\":\"NoRoute\",\"routes\":[]}");
    }

    @Test(expected = IOException.class) public void rejectsEmptyGeometry() throws Exception {
        NavigationClient.parseRoute("{\"code\":\"Ok\",\"routes\":[{\"geometry\":{\"coordinates\":[]}}]}");
    }
}