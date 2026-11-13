@{
    Jdk = @{
        Url = 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8.zip'
        Sha256 = '636bdc13dc649ddc7d5921c5c7d23cca6e44fd19d662b45cc9c02135a6c96c42'
        Archive = 'jdk-25.0.5.zip'
        Directory = 'jdk-25.0.5+8'
    }
    JavafxSdk = @{
        Url = 'https://download2.gluonhq.com/openjfx/25.0.3/openjfx-25.0.3_windows-x64_bin-sdk.zip'
        Sha256 = '839ca4a3220cfd5d30cba1bc54f222fa2f0b3033c7a14300f0728cf2b385094c'
        Archive = 'openjfx-25.0.3-sdk.zip'
        Directory = 'javafx-sdk-25.0.3'
    }
    JavafxJmods = @{
        Url = 'https://download2.gluonhq.com/openjfx/25.0.3/openjfx-25.0.3_windows-x64_bin-jmods.zip'
        Sha256 = '0bf9b83260b85607a9ba200124debabd9cdb013cbc0d659e62a20192a7137907'
        Archive = 'openjfx-25.0.3-jmods.zip'
        Directory = 'javafx-jmods-25.0.3'
    }
    Native = @{
        Url = 'https://ziglang.org/download/0.14.1/zig-x86_64-windows-0.14.1.zip'
        Sha256 = '554f5378228923ffd558eac35e21af020c73789d87afeabf4bfd16f2e6feed2c'
        Archive = 'zig-0.14.1.zip'
        Directory = 'zig-x86_64-windows-0.14.1'
    }
    Installer = @{
        Url = 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip'
        Sha256 = '6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31'
        Archive = 'wix314-binaries.zip'
        Directory = 'wix-3.14.1'
        Flat = $true
    }
    Core = @{
        Url = 'https://github.com/tn5250j/tn5250j/archive/d160492db8adec5d7cf309c6f4feefa162e6565d.zip'
        Sha256 = '5eb18ddb77e6c29f20fa7a1f9a327b01ea44fd1e5e125de536c860c3be054dd4'
        Archive = 'tn5250j-source.zip'
        Directory = 'tn5250j-d160492db8adec5d7cf309c6f4feefa162e6565d'
    }
}
