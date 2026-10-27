@{
    Jdk = @{
        Url = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip'
        Sha256 = 'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'
        Archive = 'jdk-21.0.12.1.zip'
        Directory = 'jdk-21.0.12.1+1'
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
