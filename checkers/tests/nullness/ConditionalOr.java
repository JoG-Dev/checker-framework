import checkers.initialization.quals.Initialized;
import checkers.nullness.quals.*;

public class ConditionalOr {

  void test(@Nullable Object o) {
      if (o == null || o.toString() == "...") {
          // ...
      }
  }

}