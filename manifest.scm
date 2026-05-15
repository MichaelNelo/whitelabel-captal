(use-modules (guix packages)
             (guix profiles)
             (guix download)
             (guix build-system copy)
             (guix build-system gnu)
             (guix build-system trivial)
             (guix gexp)
             (gnu packages java)
             (gnu packages elf)
             (gnu packages compression)
             (gnu packages sqlite)
             (gnu packages video)
             (gnu packages)
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

;; Pre-built CLI assembly JAR. Run `./mill cli.assembly` before `guix shell -m manifest.scm`.
(define-public captal-cli
  (package
   (name "captal-cli")
   (version "1.6.0")
   (source (local-file "out/cli/assembly.dest/out.jar" "captal.jar"))
   (build-system copy-build-system)
   (inputs (list openjdk21))
   (arguments
    (list
     #:install-plan #~'(("captal.jar" "lib/captal/captal.jar"))
     #:phases
     #~(modify-phases %standard-phases
         (add-after 'install 'create-wrapper
           (lambda* (#:key inputs outputs #:allow-other-keys)
             (let* ((out (assoc-ref outputs "out"))
                    (bin (string-append out "/bin"))
                    (jar (string-append out "/lib/captal/captal.jar"))
                    (java (search-input-file inputs "bin/java")))
               (mkdir-p bin)
               (call-with-output-file (string-append bin "/captal")
                 (lambda (port)
                   (format port "#!/bin/sh\nexec ~a -jar ~a \"$@\"\n" java jar)))
               (chmod (string-append bin "/captal") #o755)))))))
   (home-page "https://github.com/style/whitelabel-captal")
   (synopsis "Whitelabel captive portal provisioning CLI")
   (description "CLI tool for provisioning and deploying whitelabel captive portal locations.")
   (license license:asl2.0)))

(define terraform
  (package
   (name "terraform")
   (version "1.14.3")
   (source
    (origin
     (method url-fetch)
     (uri (string-append
           "https://releases.hashicorp.com/terraform/"
           version "/terraform_" version "_linux_amd64.zip"))
     (sha256
      (base32 "0kfvkrlbccdb5jlxp0ybjxsxh8qhrjifrb1j8ywnifsi49h2m2qp"))))
   (build-system trivial-build-system)
   (native-inputs (list unzip))
   (arguments
    (list
     #:modules '((guix build utils))
     #:builder
     #~(begin
         (use-modules (guix build utils))
         (let* ((out (assoc-ref %outputs "out"))
                (bin (string-append out "/bin"))
                (source (assoc-ref %build-inputs "source"))
                (unzip (string-append (assoc-ref %build-inputs "unzip") "/bin/unzip")))
           (mkdir-p bin)
           (invoke unzip source "-d" bin)
           (chmod (string-append bin "/terraform") #o755)))))
   (synopsis "Infrastructure as Code tool")
   (description "Terraform enables infrastructure provisioning using declarative configuration files.")
   (home-page "https://www.terraform.io/")
   (license #f)))

(packages->manifest (list openjdk21
                          sqlite
                          ffmpeg
                          coursier
                          captal-cli
                          terraform
                          (specification->package "awscli")
                          (specification->package "jq")))
