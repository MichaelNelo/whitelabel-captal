(use-modules (guix packages)
             (guix download)
             (guix build-system copy)
             (guix gexp)
             (gnu packages java)
             (gnu packages elf)
             (gnu packages compression)
             ((guix licenses) #:prefix license:))

(define-public coursier
  (package
   (name "coursier")
   (version "2.1.25-M23")
   (source (origin
            (method url-fetch)
            (uri (string-append "https://github.com/coursier/coursier/releases/download/v" version "/cs-x86_64-pc-linux.gz"))
            (sha256
             (base32
              "0f9skv300zsf3xx89h5ps7480ki4mzlizqvndrj5w2fnq6k4rhmq"))))
   (build-system copy-build-system)
   (inputs (list zlib))
   (native-inputs (list patchelf))
   (arguments
    '(#:install-plan
      '(("cs-x86_64-pc-linux" "bin/cs"))
      #:phases
      (modify-phases %standard-phases
                     (add-after 'install 'patchelf
                                (lambda* (#:key inputs outputs #:allow-other-keys)
                                  (let* ((patchelf (search-input-file inputs "bin/patchelf"))
                                         (zlib     (dirname (search-input-file inputs "lib/libz.so.1")))
                                         (out (assoc-ref outputs "out"))
                                         (coursier (string-append out "/bin/cs")))
                                    (chmod coursier #o755)
                                    (invoke patchelf
                                            "--add-rpath"
                                            zlib
                                            coursier)))))))
   
   
   (home-page "https://get-coursier.io/")
   (synopsis "Pure Scala Artifact Fetching")
   (description "Coursier is the Scala application and artifact manager. It can install Scala applications and setup your Scala development environment. It can also download and cache artifacts from the web.")
   (license license:asl2.0)))

(packages->manifest (list openjdk21
                          coursier))
