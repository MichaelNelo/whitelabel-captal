(use-modules (guix gexp)
             (gnu packages)
             (guix packages)
             (guix profiles)
             (guix download)
             (guix build-system copy)
             (gnu packages compression)
             (gnu packages base)
             (gnu packages bash)
             (gnu packages curl)
             (gnu packages version-control)
             (gnu packages elf)
             (gnu packages gcc)
             (gnu packages commencement)
             ((guix licenses) #:prefix license:))

(define mise
  (package
    (name "mise")
    (version "2026.3.13")
    (source
     (origin
       (method url-fetch)
       (uri (string-append
             "https://github.com/jdx/mise/releases/download/v"
             version "/mise-v" version "-linux-x64.tar.gz"))
       (sha256
        (base32 "0qj790cycs0yyjazpm7rpllw86mkswzp6971j1ccysizbhb25510"))))
    (build-system copy-build-system)
    (arguments
     (list
      #:install-plan #~'(("bin/mise" "bin/mise"))
      #:phases
      #~(modify-phases %standard-phases
          (add-after 'install 'patch-elf
            (lambda* (#:key inputs outputs #:allow-other-keys)
              (use-modules (guix build utils))
              (let* ((out (assoc-ref outputs "out"))
                     (mise (string-append out "/bin/mise"))
                     (patchelf (search-input-file inputs "/bin/patchelf"))
                     (ld-so (search-input-file inputs "/lib/ld-linux-x86-64.so.2"))
                     (libc-lib (dirname ld-so))
                     (libgcc (search-input-file inputs "/lib/libgcc_s.so.1"))
                     (gcc-lib (dirname libgcc))
                     (rpath (string-append libc-lib ":" gcc-lib)))
                (invoke patchelf "--set-interpreter" ld-so mise)
                (invoke patchelf "--set-rpath" rpath mise)))))))
    (native-inputs (list patchelf))
    (inputs (list glibc `(,gcc "lib")))
    (home-page "https://mise.jdx.dev/")
    (synopsis "Polyglot runtime manager (asdf rust clone)")
    (description
     "mise is a polyglot tool version manager. It replaces tools like
asdf, nvm, pyenv, rbenv, etc.")
    (license license:expat)))

(packages->manifest
 (list mise
       (specification->package "awscli")))
