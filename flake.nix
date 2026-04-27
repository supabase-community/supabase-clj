{
  description = "Supabase Clojure SDK development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05-small";
  };

  outputs = {nixpkgs, ...}: {
    devShells = nixpkgs.lib.genAttrs nixpkgs.lib.systems.flakeExposed (system: let
      pkgs = import nixpkgs {inherit system;};
    in {
      default = pkgs.mkShell {
        packages = with pkgs; [
          temurin-bin-21
          clojure
          clj-kondo
          clojure-lsp
          babashka
        ];
      };
    });
  };
}
