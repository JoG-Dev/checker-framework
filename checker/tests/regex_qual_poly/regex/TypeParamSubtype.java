import org.checkerframework.checker.experimental.regex_qual_poly.qual.*;
import java.util.*;

class TypeParamSubtype {
    // These are legal because null null has type @Regex String
    // <T extends @Regex String> void nullRegexSubtype(Collection<T> col) {
    //     //:: error: (argument.type.incompatible)
    //     col.add(null);
    // }
    //
    // <T extends String> void nullSimpleSubtype(Collection<T> col) {
    //     //:: error: (argument.type.incompatible)
    //     col.add(null);
    // }

    <T extends @Regex String, U extends T> void nullRegexSubtype(Collection<T> col, U u) {
        col.add(u);
    }

    <T extends String, U extends T> void nullSimpleSubtype(Collection<T> col, U u) {
        col.add(u);
    }

}
