rootProject.name = "okay-tiny"

// a service of beans, transactions and a controller written with okay-tiny: tests, not a part of the library
include("spring-sample")

// direct style: a compiler plugin that rewrites methods marked @Direct into chains of flatMap; apart from the library
include("direct")
