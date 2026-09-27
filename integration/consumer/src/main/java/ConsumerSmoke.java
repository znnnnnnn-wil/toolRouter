import io.github.toolrouter.BM25ToolRouter;
import io.github.toolrouter.InMemoryToolRegistry;
import io.github.toolrouter.ToolDefinition;
import java.util.List;

/** Standalone consumer that resolves the installed library and its dependencies. */
public final class ConsumerSmoke {
    public static void main(String[] args) {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new ToolDefinition("weather_forecast", "Predict weather", List.of("weather"), null));
        try (BM25ToolRouter router = new BM25ToolRouter(registry)) {
            if (!router.route("weather", 1).get(0).tool().name().equals("weather_forecast")) {
                throw new IllegalStateException("Library dependency did not route as expected");
            }
        }
    }
}
