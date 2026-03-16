# Arduino Maven compiler 

This is a simple maven mojo that allow maven to compile lib and sketch for arduino inside the maven compile step. This was designed for CI-friendly, repeatable builds — inspired by frontend-maven-plugin. 

This is part of the Open Lab platefrom i try to create.

## Roadmap

- [ ] Support external Arduino library download and deploy on Maven repository
- [ ] Multi-board support (multiple Arduino board targets in a single build)
- [ ] Unit test support (run Arduino unit tests as part of the Maven test phase)

